# Frontend UI conventions

Decision records governing the board's URL surface, user feedback, undo, dialogs, action emphasis,
colour roles, type scale, depth, CSS precedence, and replay. These are **rules, not descriptions** —
when code and this document disagree, one of them is a bug. Keep them short; they exist to stop the
same argument being re-litigated per PR, and each carries the measurement that settled it.

Verified against code on 2026-10-22. Source: `frontend/src/styles/`, `frontend/src/composables/`,
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

- **One authority per value.** The URL owns `run`/`trace`/`finding`. History openers navigate
  and load the addressed artifact; refresh and browser navigation use the same loaders.
  A verification completion adopts its received verdict, staleness evidence and confirmed saved
  run address together, without refetching that verdict. Earlier history reads are invalidated.
  Opening a saved simulation trajectory also adopts its confirmed run address, including when a
  newly completed task opens playback. Local-only simulation playback clears an unrelated address.
  Synchronous and asynchronous completion use the same adoption path; a background completion
  or one arriving during replay does not change the selected artifact. An unconfirmed save keeps
  a local result and clears any unrelated result address rather than pretending it can be restored.
  Completion callbacks recheck their Board/polling ownership after history refreshes as well as
  result reads. Leaving the Board during either wait must not open a replay, change the new page's
  URL, or present a failure from the retired view.
- **Every saved verification replay entry names its parent run and selected trace.** The result
  dialog reuses the history trace opener and its ownership and mutation checks. Refresh restores
  that trace's frozen scene; an unsaved counterexample remains playable locally without a saved address.
- **Every exploration replay entry names its parent run.** Both the result dialog and
  history row navigate through the same finding opener before loading its frozen replay
  evidence. Refresh, browser history, and returning from the account page therefore restore
  the selected finding rather than only its parent result.
- **Opening an artifact is `push`** (back should close it). Correcting or clearing a stale
  param is `replace` (no junk history entry).
- **Invalid, stale, or unauthorized params degrade to the plain board** with one
  page-level explanation — never a fabricated empty result, never a silent redirect loop.
- **Back/forward must restore the surface**, not just the address bar.
  Tear down the previous playback, timer and frozen canvas before loading the selected artifact,
  while preserving the incoming address. Returning to a result must not leave replay locks active.
- **A stale verification result leads to current-board settings.** Its **Configure current board verification**
  action dismisses the old result and its owned deep link, then opens the existing verification
  panel with the selected settings. It does not submit a task or adopt the old run's snapshot.
  The user starts the new run after reviewing the current scene and settings.
- **A deleted run must lose its deep link and every visible surface.** `close*` is an internal
  transition that preserves the URL; `dismiss*` clears the result target it owns while playback
  is absent. Dismissing an unrelated error must preserve the replay URL. Replay teardown must also clear the URL
  so the watcher cannot reopen deleted evidence.
  Assistant and other-tab deletions arrive through a **successful** history reload.
  `reconcileOpenRunAgainstHistory` dispatches all three run kinds; `deletedRunSurfaces.spec.ts`
  checks that every declared reconciler is wired. A failed or empty reload is not deletion evidence.
  Exploration also requires `hasMore=false`, because a run absent from a partial paginated list
  may still exist. See [exploration history](../api/fuzzing.md).
  Read verification/exploration run ids from the dialog or replay evidence (`verificationTaskId` /
  `fuzzTaskId`), since opening replay clears the dialog ref. Use `activePlaybackKind` to choose
  teardown and wording: evidence refs can survive while another surface is visible.
  Simulation uses `lastSimulationResult`, which outlives its surfaces, so it additionally checks
  visibility. Notices distinguish a closed result panel from ended playback, and candidate exploration
  from formal verification.

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
| Complex file diagnostic, with no in-page editor | `acknowledgeFailure` | Keeps coordinates and causes readable while the user corrects the external file. |
| Permanent account deletion | `AccountDeleteDialog` | Needs password + typed confirmation, so it is a bespoke form, not a message box. |
| Background async task | task indicator / run history | Not a toast per state change. |

### Rules

- **Persistence does not require a card.** A field error or rejected candidate belongs as
  ordinary inline text inside its existing editing area. A separate card earns its space
  when it groups a result, several related diagnostics, or recovery actions with their
  continuing state. Use a message for a short event with no information the user needs
  to retain; use a modal for a decision or a detailed report with no suitable page owner.
  Do not nest an error card inside a candidate card merely to say that applying it failed.
- **Never `window.alert` / `confirm` / `prompt`.**
- **One mechanism per outcome.** Never a toast *and* a banner *and* an inline error for the
  same failure.
- Entering run settings or a repair dialog retires earlier success and informational toasts.
  A confirmed undo or redo replaces earlier completion receipts with the latest edit result;
  submitting verification also retires these receipts when settings stayed open during edits.
  Warnings and errors remain. This keeps a rapid repair/apply/undo/redo workflow readable.
- **Fix searches are interactive work.** Closing the dialog requests cancellation; hard refresh
  and page leave also send the same owner-pinned stop using keepalive transport, because page
  teardown does not unmount Vue and can abort an ordinary XHR. A pending stop gets its own
  keepalive delivery rather than being mistaken for a confirmed cancellation. Only accepted
  cancellation or terminal evidence releases tracking. Search-budget exhaustion, timeout and
  inconclusive results use caution feedback; transport, model-generation and solver failures use
  error feedback. The polite status region announces observed phase changes, not every elapsed
  second. Mobile strategy and footer controls use compact wording with full accessible names.
  The narrow removal button names the destructive action; its accessible name and confirmation
  retain the complete action wording. Removal confirmation lists the selected proposal's rules as
  wrapping text inside a bounded scrolling body, with confirmation controls outside that body;
  cancelling keeps the proposal available without writing. Condition adjustments use full-width
  explanations whose verbs identify the change. Do not repeat those verbs in side badges that
  squeeze device names and conditions into narrow columns. During apply, strategy
  selection, alternatives, search ranges and search actions remain locked to the reviewed proposal.
  A response from an old authentication session cannot adopt rules, close the current dialog or
  display feedback in the new session. Incomplete searches use plain next-step wording; an empty
  executed-rule list is neutral information and does not imply a passed verification.
  Fault-localization counts appear only after a successful load or trusted search result; loading and
  failure never imply zero related rules. Remaining pre-existing violations describe only the listed
  requirements that this option leaves failing, without claiming that no other requirement was repaired.
  Rejected changes explain that preserving already-satisfied requirements is part of acceptance.
  Confirmed apply adopts the returned rules through the shared semantic commit, including canvas
  edges, inspector focus, verdict staleness and journal availability; it does not read rules again.
  The success message names the applied edit and undo availability; the proposal owns its evidence
  and remaining violations. Condition-change and removed-rule names wrap inside narrow cards.
  Fault-localization and strategy-request failures retain message keys and diagnostic snapshots;
  changing language updates their summary and next step without repeating the request. Raw service
  prose and field errors stay in one technical disclosure. A search response whose outcome is unknown
  stays distinct from a confirmed cancellation or a completed request without a result; these states
  use warning tone, while actual request failures retain error tone. The failure card owns its alert
  announcement instead of repeating it in the progress live region. The disabled retry points to that
  card while tracking still owns the request.
  Typed preflight unavailability or a busy formal-operation permit keeps the signed proposal
  available for retrying apply;
  a changed model still requires verification and a new proposal. Unclassified infrastructure errors
  remain unconfirmed writes and trigger reconciliation. Failure acknowledgements do not repeat an
  identical title as their body summary. The close icon uses the shared keyboard-accessible hint.
  Once all strategies finish with a proof, inapplicability or a search-budget outcome and no verified option, the outcome
  gives a next step: review the failing states and opposing rules or requirements, then adjust inputs
  and verify again. Budget-limited results retain their unfinished-search title and reason; the review
  guidance must not imply impossibility or direct users to search controls that the strategy does not expose.
  Server cancellation and
  repair acceptance semantics belong to
  [verification.md](../api/verification.md) and [auto-fix.md](../architecture/auto-fix.md).
- **Helper text must add information.** Do not repeat a field label, section title, button,
  or obvious action in a subtitle, placeholder, or empty-state instruction. Keep constraints,
  non-obvious interactions, domain semantics, action consequences, and recovery instructions.
  Render a validation or disabled-action reason once and reference it with `aria-describedby`;
  removing redundant copy must not remove accessible labels or required error feedback.
  Tool-result entries retain their outcome and any concrete details; an entry with no additional
  details does not need a generic success paragraph beside its outcome badge.
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
- **A result without a confirmed save cannot be deferred only to persisted history.** If a synchronous run
  completes during another replay, the current replay keeps its run and frozen scene. A confirmed
  saved result remains in history. An unsaved or unconfirmed save remains in a persistent result
  notification with explicit Open and Discard local result actions; ordinary toast cleanup does
  not discard it. The notification owns the result until opened, discarded, or the owning Board
  view is left. Opening rechecks account ownership and playback admission, then adopts the same
  simulation result through the normal playback path, or presents the verification verdict with
  its original traces and logs. Freshness compares the current scene with the original submission,
  including changes while the notification was open. Discard removes the local copy; an unknown
  save outcome remains unknown. No run completion automatically replaces an already visible replay.
- **Run failures follow the same ownership rule.** An idle result surface owns its error
  without a duplicate toast. During another replay, a persistent notice retains the failure
  and its diagnostics until explicitly viewed or discarded. Viewing rechecks account and
  playback admission; it does not invent a saved run or replace an active replay.
- Same semantic ⇒ same component, wording shape, icon, button order, focus behaviour.
- Error text keeps what helps the user act; **no stack traces, no internal identifiers.**
  Field paths and declared causes stay with correction feedback; logs, status codes
  and stack traces belong in Technical Details or the console.
- Prefer stable reason codes over free-text; fall back through `utils/userMessage.ts`.
- **A failure that needs correction stays beside the action.** Template and device
  import feedback and rule/specification save conflicts stay in their editing area,
  preserving the draft. Scene-file rejections use `acknowledgeFailure`; recommendation,
  run and fix surfaces retain diagnostics in their owning panel or dialog.
  `FailureDetails` shows the declared cause and field errors immediately; HTTP status,
  codes and logs remain expandable. Keep the localized summary and a short applicable
  next step. Do not repeat the cause in the expandable section or list hypothetical causes.
  A confirmed template import with an unavailable catalog leaves the page's Retry
  control reachable and retries the catalog read, never the confirmed write.
  A failed recommendation is distinct from a successful response with no candidates.
  Applying a candidate has its own failure feedback beside that candidate; it does not
  turn a successful generation into a failed generation. Exact duplicate-rule rejection
  stays in the editor; a similarity warning that offers Save Anyway still needs a decision.
  Failed section reads retain Retry and their actual cause; failure must not be rendered
  as a successful empty result. Concrete account, conversation and task recovery flows
  belong in the [integration guide](frontend-integration.md).
  JSON coordinates come only from the parser's explicit location, never from guessed
  positions or text quoted from the input. Conflict advice follows the server's reason
  code; a generic conflict must not suggest changing a particular object type.
- **A disabled submit needs a visible reason.** Derive both from one computed value (e.g.
  `specConditionBlockedReason`) so the button and the message can never disagree, and point
  the control at it with `aria-describedby`. If the form already blocks the action, the
  handler must not also toast — that is the duplicate-feedback rule.
  A configured rule condition must be added or its inputs cleared before saving the rule;
  saving cannot silently omit an extra condition still in the input form. Reuse that form's
  blocking hint rather than repeating it beside Save. Specification template selection warns
  that switching templates clears added conditions, only while such conditions exist.
- Never use a client-side `error.message` as primary failure copy. A transport or programming failure
  ("connect ECONNREFUSED …", a `TypeError`) is technical diagnostics; show the backend's
  `message` only when `utils/userMessage.ts` says it matches the active locale, else a
  translated fallback. Declared causes, field errors and parser diagnostics are shown
  as text through `errorDetails`; never dump request config,
  credentials or arbitrary response objects into that surface.
  Pass the visible summary to `FailureDetails.message` so an identical cause or technical-detail
  line is not repeated; distinct causes, field diagnostics and protocol details remain available.
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
- **A held node pointer gesture owns its preview until release or cancellation.** Node keyboard
  commands and context menus must not move, resize, delete or open a node while that gesture is active. Board undo/redo
  checks the same active layout ownership before queueing and again when its queue slot is reached,
  so server history cannot change underneath an uncommitted drag or resize. While undo/redo is pending,
  the existing canvas interaction lock prevents a new node gesture from starting before reconciliation.
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
  not remove one. Do not write tests or features that assume otherwise. Confirmation buttons expose
  their localized text as the accessible name; decorative check/close icons are hidden from it.
- **Availability is a query.** `GET /board/edits/availability` returns the two booleans and empty
  node, Environment Pool, rule, and specification collections; it must never be treated as an
  authoritative board update. Because this read runs outside the mutation queue, an authoritative
  mutation response invalidates every read already in flight, and only the latest-started concurrent
  read may update the affordance.

## 4. Dialogs are one surface with three sizes and four tones

Control Center creation tools share the panel's scroll region. Rule creation lives in
its Rules tab as an inline `RuleBuilder`: trigger conditions, target action, optional
content, preview, and creation actions. It leaves the canvas available, retains drafts
when switching tabs or collapsing the panel, and clears them after a confirmed save or
an explicit Reset draft. Save failures stay beside the unchanged draft and receive
focus and scroll into view. Hidden or
read-only editors retire pending checks so late confirmations cannot submit a rule.
The inspector's Add rule action opens this same control section.

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

- **Titles, icons and close controls share one centered row.** Dialog subtitles occupy the next
  row under the title; long metadata must not move the icon or close control away from the title.
  The same vertical centering applies to tool-panel headers and their contextual help buttons,
  including wrapped titles.
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
- Dismissal uses the same inset icon face as [side-panel actions](#13-workspace-shell-and-concise-copy),
  with a keyboard outline; its hover surface does not fill the entire interaction target.
- **Primary action last, always.** A confirm button that changes position between surfaces is the single
  most legible symptom of unrelated dialogs.
- **Related footer actions stay together.** Use the footer gap instead of pushing the primary action
  to the opposite edge. On narrow screens, retain each label's natural width and wrap whole buttons
  before squeezing their words into equal columns. Icons do not shrink beside a wrapped label.
  Device details group Rename and Delete as secondary routes before the final Close action; the
  deletion confirmation owns the destructive fill.
- **Initial focus follows the decision.** Mark the intended control with `autofocus`; the shared focus
  manager selects it only when it is available. Logout starts on Cancel, while the account-deletion
  entry remains separately reachable. Tab still follows DOM order and closing restores the opener.
- **Collapsed content leaves keyboard and reading order.** The assistant history sidebar uses
  `inert` and `aria-hidden` while collapsed; its toggle reports `aria-expanded`. A zero-width or
  transparent panel alone still exposes hidden buttons to the shared initial-focus manager.
  If focus is inside history when selection or resizing collapses it, move focus to the visible
  toggle before applying `inert`.
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
  `--iot-target-min` token rather than repeating the literal — two plausible literals with no link
  between them is how five button heights coexisted here before.
  `dialogSurfaceConsistency.spec.ts` fails if a modal skips the layer (counted per dialog, not per file), if
  a surface goes translucent, if the narrow block re-docks, if the token is bypassed, or if an orphan class
  is left on markup.

Verification summaries become label/count rows in narrow dialogs. The SMV artifact description
retains a readable column before its download action wraps; it must not shrink to a vertical strip.
Counterexample actions wrap as necessary and use shared dialog buttons: replay is the primary action,
rule repair is secondary, and the evidence verdict carries the outcome colour. Technical disclosures
retain full interaction targets. Run failures group their headline and `FailureDetails` in one notice;
`board-run-notice` and history notices share the body-copy tier and padding rhythm.
Browser geometry is covered by `e2e/run-surface-layout.spec.ts`.

## 5. Action emphasis

A user needs to know which control is *the* next step. If several controls claim that role, none of
them holds it.

This was measured, not assumed: on an empty Board, 14 of 38 visible controls carried primary-action
weight, 8 of them in the action dock, because every dock button was an opaque 700-weight fill in its
own hue. The dock already grouped run actions and AI suggestions separately in markup, but both
groups were painted identically, so the grouping was invisible.

### Rules

- **Emphasis follows consequence.** The submit action inside a workflow may carry a primary fill.
  Toolbar entries open settings, results or suggestions and share a neutral surface, visible border
  and flat treatment.
- **Name the next action accurately.** Entries that only open run configuration say **Open** or
  **Configure** in both languages, including handoffs from exploration evidence and stale results.
  **Run**, **Start** and **Generate** belong to controls that submit work after configuration.
  Synchronous mode is labeled **Wait for result**, paired with **Background task**; selecting
  either mode changes the form and does not start a run.
- **Demote rather than hide or disable.** A quieter control is still visible, focusable, named, and
  full-size. Hiding it costs discoverability, and a `disabled` element is unreachable for assistive
  technology, so neither is a substitute for lowering emphasis.
- **Categories use names, icons and groups.** Execution, history and suggestions keep their own
  groups without different button grounds. Do not add a palette for scene, rule, device and spec categories.
- **State never depends on colour alone.** An open panel adds a short, independent marker alongside
  its tint and border. Do not simulate it with an inset shadow; a running tool keeps its activity indicator.
- **Do not spend a new hue on a new category.** Eight saturated hues accumulated one feature at a
  time, each locally reasonable. A new dock entry uses the shared control and belongs in the relevant group.

`views/board/actionDockHierarchy.spec.ts` pins the distinction between run actions, history and suggestions.

### Run actions share a treatment; outcomes retain their meaning

| Control | What it returns | Group |
| :--- | :--- | :--- |
| **Verification** | a formal NuSMV proof or a counterexample | Run |
| **Simulation** | one concrete trace; proves nothing | Run |
| **Explore** | bounded *candidate* counterexamples | Run |
| **Run History** | a read-only view of past results; writes nothing | Review |

Verification, simulation and exploration intentionally share the run-action treatment. Their tooltips
name the different results: a formal verdict, one execution, or bounded candidate evidence. Results must
preserve that distinction; finding no candidate is not proof of safety. AI suggestions propose changes
for review. All entries use `board-tool-button`, including shared hover, open, disabled and focus states.
Quiet controls still need a visible boundary and full target. Reduced motion retains the marker and
removes its transition and the pointer press movement.

Verification and simulation settings use `board-run-panel`: a fixed header, a scrolling options body,
and a fixed run-action footer. Exploration keeps its run action and workload feedback in the same
fixed footer rhythm, outside the scrolling target list and parameters. Its introductory caveat uses
a heading tooltip, centered with the heading and section icon; result caveats remain inline.
Narrow exploration parameters stack rather than
squeezing long labels into paired columns. Exploration configuration details stay beside the parameters;
the footer keeps a short blocking prompt so long budget calculations cannot crowd out editable fields.
Attack-configuration errors appear once beside the affected fields;
the disabled run action references that same explanation. Other blocking reasons appear in the footer.
Setting cards have one border and no
nested raised shadow. `board-run-mode` owns option-label typography and selected, hover, focus and
opaque disabled states; wrapping labels keep inner spacing. Exact attack-point rows retain the same
target floor and keep unavailable labels readable. `board-run-range` separates its full interaction
area from its compact track; steppers and task cancellation reuse the panel icon action.

Local progress for verification, simulation, and exploration retains translation keys and translates
them when rendered. A language change must update the current phase, cancellation state, and task
indicator without waiting for another response. Exploration target recovery names only the eligible
specifications it selects; a target-selection error stays beside those targets instead of repeating
below the form or expanding unrelated advanced settings.

Recommendation panels keep their header and close action outside a single scrolling body. Long
results use the available viewport height rather than a fixed 500/560px cap; short screens must not
scroll both the whole panel and its body. The formal-verification caveat belongs with the returned
candidates, without repeating it in the scene-panel header.
Escape on an open help trigger dismisses that tooltip first; a subsequent Escape can close its
enclosing panel or dialog.

---

## 6. Ink and paper: a role has two halves

The copper palette uses warm paper surfaces in light mode and warm charcoal in dark mode.
The monochrome palette uses achromatic surfaces, ordinary ink and controls. Brand identity, device
artwork, syntax highlighting and meaningful success, warning and danger signals retain their colors.
Palette and light/dark mode are independent.
`styles/base.css` is the color owner: semantic roles, each palette's neutral ramp and Element Plus
aliases live there. Tailwind's neutral families consume that ramp; components do not define another
brand palette. Welcome, authentication, the workspace, replay, chat and dialogs share these roles.
`--brand` keeps the topology mark independent of the control accent. `--iot-node-tint` gives light
node cards a faint tint while dark cards retain enough separation; canvas grids remain subdued.
Shared card and overlay grounds are opaque. Light modes use bright paper with strong ink; dark modes
separate the canvas from raised surfaces and keep muted text legible without heavy shadows.

### Rules

- **Color identifies state and action priority.** Ordinary sections, categories and metadata are neutral.
  The palette accent identifies selection, focus, progress and primary actions. Danger, warning and success colors
  require an actual outcome or risk; do not use them merely to distinguish panels.
- **Repeated review actions are quiet.** Run history's Watch task, View result and Replay controls use
  `board-action-secondary`: a neutral fill and visible border, with shared hover and focus states.
- **History has one reading order.** It opens on Historical results; Background tasks remains the
  explicit lifecycle view. Cards share kind/date headers, restrained outcome/provenance badges,
  a model-comparison line, scope counts, and a bottom action row. Run details and counterexample
  evidence use adjacent keyboard-operable disclosures; opened content spans the card width.
  Complete specification names, diagnostics, replay/fix actions, and SMV downloads remain available
  without making every closed card repeat all run parameters.
  Opening a result and returning retains the history filter, expanded disclosures and scroll position
  for the current board session. Cached history belongs to the signed-in user and is discarded when
  that owner changes. Refreshing summaries keeps existing cards mounted; the refresh control reports
  loading while an initial empty list uses the loading state. Changing the result filter or switching
  between tasks and results starts the newly selected list at the top. Translation and viewport changes
  preserve the current reading context; a list scrolled to its end retains that bottom reading position
  when the viewport becomes shorter.
- **Execution status and model relation are separate.** A running task continues on its frozen
  input after a Board edit. Its status/progress do not assert that the current model still matches.
  Task and result cards use the [authoritative comparison contract](../api/verification.md#modelrunsnapshotdto)
  rather than item counts or a signature available only in the submitting tab. Semantic edits
  invalidate comparison evidence immediately and refresh it; late responses cannot restore a stale
  identity. Geometry/display-name edits preserve a match. Failed current reads and absent historical
  evidence use distinct neutral unknown notices, while a confirmed difference uses warning ink.
- **Write with the bare role; fill under white ink with `--<role>-fill`.** Dark-theme role ink is pale
  and cannot also carry a white label. Filled controls use dedicated readable fills. Primary hover darkens
  in light mode and brightens in dark mode; both remain readable. Use the dedicated destructive hover.
- **Use `--<role>-surface` and `--<role>-border` for status containers.** Tool headers remain opaque
  and neutral. Ordinary text and status ink require at least 4.5:1 against their actual ground; focus
  indicators and control boundaries require 3:1. Translucent surfaces and faded labels need separate
  measurement, since opacity changes the resulting colors.
- **Neutral hover has its own role.** Use the shared surface and ink roles in cards, dialogs and replay controls.
  Use `hover:board-control-hover`; pair muted resting ink with `hover:board-text-strong` to keep the
  darker light-theme hover readable. Icon-only controls may set the glyph's ink separately.
- **A chip owns its ink.** Do not pair a chip with a conflicting resting text class. Hover may change
  that ink through its explicit role variant. Accessible names use the same terms as visible labels.
- **Canvas identity stays restrained.** Nodes and their outgoing links use three tones of the copper
  family in copper mode and corresponding neutral tones in monochrome. Device names, icons and trace state identify them; a tone alone does not imply an outcome.
  SVG gradients, arrowheads and node tokens share `NODE_ACCENT_COUNT`.

Generated device artwork uses the shared placeholder renderer in `utils/device.ts`, including image-load
failures. Its SVG paint is self-contained because an image document cannot inherit host CSS variables.
Device names and state labels distinguish custom templates without a random hue per name.
`iot-device-artwork` identifies device and product illustrations without altering their colors.
Read-only panels keep status colors and readable text; disabled controls identify unavailable actions.
Nodes absent from a playback scene retain their artwork and use a dashed, muted outline treatment.

### Disabled: desaturate, do not fade

A disabled filled action uses `--accent-fill-disabled` and opaque label ink; fading the whole control
also fades its label. Inline filled primary actions reuse `board-action-disarmed`; read-only panel rules
must not fade their controls as a group. Quiet controls use muted ink on the neutral control surface. A disabled state
must remain understandable, with a disabled attribute and an appropriate cursor.
Read-only panels disable mutation controls individually. Navigation has its own guard; a panel-wide
disabled marker must not override enabled panel toggles, device navigation or canvas map controls.

A dialog-wide repair blocker keeps a disabled search action with an inline explanation linked by
`aria-describedby`, rather than silently removing the primary action. Its outcome states whether
the initial state already fails, existing rules cannot affect the requirement, or the original
verification was incomplete. Threshold inputs disappear because they cannot change that blocker.
The next step is to adjust the scene and verify again; switching strategies cannot repair the
same frozen evidence. Status semantics belong to [the fix API](../api/verification.md#5-auto-fix).

### Neutral surfaces and fields share role definitions

Use `board-bg-elevated` for ordinary cards, `board-bg-muted` for inset regions and diagnostic output,
and `board-bg-control` for neutral control wells. Text uses `board-text-strong` or `board-text-muted`.
These color-only defaults live in the components layer so utility state overrides can express selection
and validation without a specificity patch. The same roles work in teleported dialogs and sibling replay bars.
Card defaults also live in that layer with zero specificity; explicit text roles and hover borders take
precedence. The muted card modifier supplies inset paper without replacing the card's text role.

Text fields use `board-field`, with `--field-bg`, `--field-border`, shared focus and placeholder colors.
The border shares the control-boundary token in both palettes and modes. Range, checkbox and radio inputs
keep their own treatment. Do not recolor a container's descendants by old numeric-neutral class names.

[FieldSelect](../../frontend/src/components/common/FieldSelect.vue) owns selection fields, using the
shared field geometry and type with one native arrow rather than local icon gutters or hand-drawn arrows.
It accepts either a draft `v-model` or an
authoritative `value` plus the original native change event. Where supported, `appearance: base-select`
styles single-choice pickers:
opaque elevated paper, floating depth, bounded viewport height, wrapped option labels, a selected
checkmark and distinct hover/focus states. Explicit native `button`/`selectedcontent` content lets a
long closed value truncate while preserving space for the arrow. The browser retains selection,
type-ahead, Escape, labels
and change events; unsupported browsers keep their native picker. See the
[browser mechanism](https://developer.chrome.com/blog/a-customizable-select).
After Vue updates single-choice options, the component reapplies the current native selected index
to refresh cloned content; option text edits alone leave native `selectedcontent` stale. This does not
change the value or dispatch field events, and it never applies to multiple-selection listboxes.
Hidden prompts stay hidden in the list and use muted ink, as do disabled prompts. An enabled empty-string
choice such as **Use template default** remains a real choice. Disabled fields keep opaque text, and
invalid fields retain their error outline when focused. Device, action, state, label and operator
values keep their domain meaning; highlighting an option does not imply a safety verdict or replace
its trust/privacy classification.
Set-membership editors retain native multiple-selection listboxes and their keyboard range selection;
do not apply single-choice picker appearance to them. Selected options use paired theme paper and ink,
including native focused selection paint; disabled listboxes use muted paper and ink for their selection.
Operator/value grids reserve readable space for the relation label and stack on narrow screens.
Template and inspector search fields share the
same clear-control target and text gutter. Device-creation labels associate with their native fields.

`neutralSurfaceOwnership.spec.ts` checks the actual roles, fields, progress cards and switches in all four
palette/mode combinations, and rejects class-keyed theme repairs throughout styles and component templates.
It also checks production script literals so dynamically returned classes cannot restore old neutral colors.
`themeContrast.spec.ts` covers semantic colors and node tones; `boardToolPresentation.spec.ts` checks workflow
headings and toolbar states. Generated device artwork and Shiki syntax colors keep their own paint.
Shiki's syntax colors retain their paired background; its padded code surface must not be made
transparent over a different theme ground. `CodeBlock.spec.ts` and `chat-assistant.spec.ts` cover this.

### A domain value maps to one role, product-wide — and an empty class is not a role

Two failures that the token rules above do not cover, because both were about *which* role a value earns
rather than how a role is spelled.

- **A classification is not a hazard.** `private` and `untrusted` are provenance facts — `nusmv-model.md`
  is explicit that "`untrusted` does not mean the device is selected as compromised" — so they take
  `info`, and `attacked` keeps `danger` because a selected compromise genuinely is one. Amber was tried
  twice and rejected twice: two desktop reviews of an ordinary simulation read it as implying a security
  defect ("颜色和措辞像警告"), recorded at `SimulationTimeline.vue`'s privacy chip and again in
  `PlaybackChangesPanel`. Reintroducing `warning` for `private` in one surface makes the product
  disagree with itself about whether an ordinary phone photo is a problem.
- **Neutral is the honest default; a role has to be earned** (`board.css`). An empty-string class branch
  is a defect, not a neutral: it renders a chip-shaped element with no chip, so the same value reads as
  two different things depending on which table it lands in. The device dialog had exactly that in two
  tables that chose *opposite* values for the empty branch, so an unstyled label meant "public" in one
  and "private" in the next. Use `board-chip-neutral` when a value carries no status.

A test that pins this should assert the chips for one value share *the same* class, not that they carry a
particular one — hardcoding the role cements it locally and lets it drift from the rest of the product.

### A section header inside a dialog is one shape

Peer sections in a dialog body use a `board-section-marker` accent bar, then a `min-w-0`
wrapper holding a `board-section-title` heading and, when needed, a caption hint at `mt-0.5`,
on a `flex items-start gap-2 mb-4` row. The marker follows the section title's line box,
so title and hint form one group without making a section larger than the surface heading.
Hints explain non-obvious ownership or capability rules, such as those in the device dialog's
variables, states, APIs and specifications tables. A section that is already clear from its heading
and fields needs no hint.

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
  Panel headings use the 16px body token; section headings use the 14px control token.
  Leave at least 2px between heading levels that appear together.
- **Pick a step on the scale, not a nearby number.** `0.72rem` and `0.78rem` each appeared at multiple sites
  and matched nothing else in the product, sitting a fraction of a pixel from `0.75rem`, which twelve other
  board labels already use. A bespoke value costs a reader the ability to tell two levels apart and costs the
  next contributor a decision that was already made.
- **`text-transform: uppercase` makes text read smaller than it measures.** It costs roughly 15% of apparent
  x-height and removes word shape, so it is a poor choice for anything already near the floor, and for a
  four-character label it is most of why the label looks too small. Ordinary section labels retain
  sentence case, just like form labels and actions.

Pinned by `views/board/actionDockHierarchy.spec.ts` for the dock, which is where the tier inversion was.

### Workspace typography and control shape have one owner

Measured before consolidation, the Control Center's device labels were 11px/700,
rule labels 12px/600, and comparable fields ranged from 12px to inherited 16px.
Local border widths, shadows, uppercase text and focus rings also varied between editors.

| Role | Shared token | Weight |
| :--- | :--- | :--- |
| Panel / dialog / confirmation title | `--iot-font-base` (16px) | 600 |
| Section title | `--iot-font-control` (14px) | 600 |
| Field label / tab label | `--iot-font-label` (13px) | 600 |
| Body copy / field value / action label | `--iot-font-control` (14px) | 400 / 400 / 600 |
| Help / hover tooltip | `--iot-font-label` (13px) | 400 |
| Transient message / persistent result notice | `--iot-font-control` (14px) | 400; notice title 600 |
| Supporting copy | `--iot-font-caption` (12px) | 400 |
| Status badge | `--iot-font-caption` (12px) | 600 |
| Key numeric metric | `--iot-font-base` (16px) | 600, tabular numerals |
| Compact count / timestamp | `--iot-font-min` (11px) | 600 / 400 |

`base.css` owns surface-title and action-label typography, including Element Plus confirmations.
`board.css` owns `board-form-label`, `board-section-title`, `board-copy`, `board-caption`,
`board-badge`, `board-metric`, fields and board action geometry. `dialog.css` owns dialog
shells and uses the same type and target tokens. Typography roles do not set display or layout;
table headers retain table-cell semantics. Dense device-variable tables keep a minimum readable
width and scroll horizontally rather than squeezing short names into vertical text.
Fields use one border, radius and focus outline; primary and secondary actions share type and
a 44px minimum target. Call sites
only supply layout, icon gutters, semantic state or code fonts. Do not add local
font, border, shadow or focus utilities to reproduce those defaults. Ordinary
labels and actions retain sentence case. The 11px floor remains available for
compact counts and metadata, rather than ordinary form instructions.

The same scale applies to device details, the inspector, recommendation cards, run results,
account settings and dialogs, sign-in forms, assistant text, tooltips and message feedback.
Appearance choices and header preferences use the action-label role; their group legends use
the field-label role. Both help icons and hover hints
use the global popper surface in `base.css`; message and result-notice styles live there too.
Message CSS loads before the shared overrides in `main.ts`, so opening a lazy panel cannot
change its appearance. Transient messages centre between viewport insets, constrain their width
and wrap long text; horizontal positioning leaves the library's vertical transition intact.
Hints inside a retained, hidden panel must disable their content when the panel closes;
teleported poppers do not inherit the panel's `display: none`.
Hover hints leave Enter and Space to the wrapped control; explicit help triggers own their
keyboard interaction.
Both shared tooltip components use `components/common/tooltip.ts` for four-direction placement
fallbacks and overflow prevention on both axes, leaving an 8px viewport inset. Its explanation
caps its height and wraps long tokens; enterable help can scroll instead of extending beyond a short
window. A preferred side is not permission to crop the explanation.
Model/formula/code text keeps its monospace family, while warning, failure and selection colours
retain their domain meaning. Narrow layouts wrap content without shrinking the text tier.
Browser parity and focus checks live in `e2e/ui-contracts.spec.ts`.

Icon actions, search-clear controls, template actions and simulation steppers retain the same
44px target on desktop and touch screens, including assistant header, voice, send/stop,
session-delete and message actions. Assistant session deletion and message actions remain
visible without hover. Search text reserves a full clear-button gutter.
The desktop assistant reserves a resize corner below its composer, so the resize
target cannot overlap Send. Narrow layouts hide the handle and keep compact spacing.
Inspector entity actions remain visible without hover. Disabled controls use opaque muted ink
and surfaces, or the shared disabled fill for primary actions; hover must not reactivate their
visual feedback. Keyboard focus uses the shared two-pixel outline.
Run-setting switches paint a compact 24px track inside their 44px target; thumb
position continues to show on/off when disabled, and the disabled on track uses
the shared neutral fill.

Creation headings, entry hints and submit labels use the same verb: **Create device / Create rule /
Create specification** (**创建设备 / 创建规则 / 创建规约**). Section tabs and inventory headings
retain entity nouns. Importing existing data uses **Import**; adding a condition to a draft uses
**Add**. English interface actions use sentence case. Reuse the existing entity action message
rather than introducing a second catalog key for the same operation.

### A `clamp()` whose middle term can never win is a fixed size wearing responsive syntax

`cqmin` is a percentage of the *container*, so on a 110–137px canvas node `4.3cqmin` is 4.7–5.9px: the
declared floor was the rendered size at every viewport, and three node declarations printed 9.28px and 10px
under an 11px minimum. Before writing a sub-floor floor, compute what the preferred term evaluates to at the
container's real size.

If a test exempts the pattern, the exemption needs a measurement, not a comment. `typographyFloor.spec.ts`
exempted these on a claim that they "render at 16px", which was false — so the check certified the defect it
existed to catch. Canvas name labels counter-scale with `--node-ui-scale` so the minimum is
measured on screen. A capped world-space size defeated this at low zoom: 14.4px at a fitted
19.6% overview painted below 3px. Compact labels retain the 11px screen floor and ellipsis;
name, state, runtime and security text counter-scale below 100% zoom using the shared
caption, label and minimum font tokens. The node title and State details retain the complete
name when the overview cannot fit it.
Literal sub-floor clamp bounds have no test exemption; browser checks also measure transformed
device-name text in fitted overviews.

### Fixed playback hosts need their own layout variables

The playback terminal is a sibling of `.iot-board`. Its fixed host cannot inherit
board-scoped width or spacing variables, and a descendant selector beginning with
`.iot-board .board-timeline` cannot reach it. Inject `boardShellStyle` onto each
replay owner and target the unprefixed `.board-timeline-host` or `.board-timeline`.

`boardDockGeometry.spec.ts` checks the shared geometry values and
`timelineHostScope.spec.ts` rejects unreachable selectors. Shared role classes and
their variants also need definitions reachable from these sibling surfaces.

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
  heavy smear across the viewport. Use its border to separate it from the canvas.

Pinned by `styles/__tests__/elevationScale.spec.ts`, which covers `base.css` and `board.css`.
Scoped component elevations outside those stylesheets need their own review; public pages
use the same elevation tokens.

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
- **An accent icon rendered grey.** The former `.device-runtime-box span { color: inherit }` repair
  also matched the one span asking for
  `board-text-accent`. Measured `rgb(148,163,184)` where `--accent` is `rgb(96,165,250)`.
- **A hover state half-applied.** Role-class hover variants declared in `board.css` lost to
  `.iot-board .board-side-panel .text-slate-500`; the fix was source *order*, not specificity, and two
  attempts at raising specificity failed first.

**Layers outrank specificity entirely, and that runs the other way.** Tailwind v4 emits its utilities into
`@layer utilities`; an unlayered rule beats a layered one at *any* specificity. Proved in-browser: an
unlayered `section.probeSec` (0-1-1) overrode `.bg-slate-100`. Numeric-neutral repairs have since been
removed with their callers. Neutral defaults, cards and fields now live in `@layer components`,
below utilities, so selection, hover and validation overrides remain effective. Complete panel components
and shared role state selectors own their appearance explicitly; use §6's shared state roles.

### Rules

- Do not put a `max-width`/`max-height` in a scoped block on an element that also carries a Tailwind
  `max-w-*`/`max-h-*`. Constrain the children that need containment, not the capped element itself.
  `styles/__tests__/scopedWidthOverride.spec.ts` fails on the overlap, per axis.
- Do not recolor all text descendants to repair legacy utilities. Give each element its role directly;
  a broad scoped text selector can swallow the status and accent roles inside it.
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

## 10. Replay uses a shared bottom terminal

`PlaybackTerminal.vue` owns the layout and interaction controls for simulation,
formal counterexample and exploration-finding replay. `SimulationTimeline.vue` and
`Board.vue` retain their run-specific state and playback timers. The canvas renders
the frozen scene; the bottom terminal reserves space instead of covering it.
Simulation replay states are derived directly from the adopted run. Its manifest, diagnostics,
and trajectory share one owner even while playback is hidden; no separately writable state copy
may outlive or disagree with that run.
Synchronous results and persisted traces must contain their initial state before the API boundary
admits them. Zero transitions still produce one state; an empty trajectory is an explicit failure,
so successful completion has no separate empty-state presentation path.

| Terminal view | Content |
| :--- | :--- |
| **Changes** | Group previous/current device values, environment changes, and automations executed to reach the selected state. Exploration inputs are a separate group. |
| **State details** | Group complete device state-machine values and reported readings, actual home environment values, and source/sensitivity labels or compromised links, including unchanged values. |
| **Run info** | Counterexamples retain the violated requirement, checked expression, model scope and generation diagnostics, plus a link to the owning verification run. Simulations retain actual/requested transitions, model scope, diagnostics and the executed SMV download. |

Playback, previous/next state, numeric seeking, the numbered timeline and violation location
occupy the persistent canvas-top strip, including while terminal content is hidden. The replay owner
teleports transport, timeline and violation location into that strip so controls and the animated state keep one owner.
The timeline labels its recorded run scene, including while the terminal is hidden. Contextual help explains
that exiting returns to the current board. State and group navigation, and the compact Exit control,
use shared pointer and keyboard hints without repeating their instructions in the terminal body.
Transport and timeline sit beside each other when the canvas corridor permits; narrow corridors
stack the timeline beneath transport, with restore at the timeline's right edge while the terminal
is hidden. Locate and Exit share the transport's baseline in a wide strip; compact layouts reserve
separate positions for Locate and Restore beside the timeline. The same layout decision drives the reserved canvas inset,
including after sidebars expand or collapse.
In compact windows, sidebar headers start below the full-width strip so their
Expand controls cannot be covered by Exit playback or seeking controls.
The tab row keeps runtime attack status when applicable, concise navigation help and the collapse
action at its right edge. Model coverage has one home in Run info. Incomplete models, unavailable semantics and shortened
simulations remain explicit outside the tab content. There are no separate counterexample-details
or successful-simulation-details dialogs. Do not recreate duplicate state tables, internal ids,
repeated state totals or generic explanatory prose when redistributing run content. Help describes
navigation; scope and diagnostics are readable content, not tooltip-only facts.
Exploration replay keeps its candidate-evidence notice visible across every view. Do not apply
formal model-completeness warnings to exploration findings, which do not carry that field.
An arriving run failure emits its error notice while preserving active playback and its URL.
It opens a result error surface only when playback is absent.
Retained failure notifications translate their heading, summary and actions at render time;
opening one later uses the current language and the original diagnostic snapshot.
Run info uses two columns when the terminal is wider than 640px and a single column below that
width. A lone section and diagnostic disclosures span the available width without reserving empty columns.
Tabs use `tablist`/`tab`/`tabpanel` semantics;
Left/Right, Home and End move selection and keyboard focus together. Changing views
preserves the selected state and autoplay. Step changes use `PlaybackChangesPanel.vue`
inside the terminal; they have no floating position, drag handle or separate dismissal state.

The terminal uses shared spacing, font, surface, accent, border and action-radius
tokens. All three tabs use `clamp(14rem, 32dvh, 20rem)` in normal windows.
Windows below 600px tall use `clamp(7rem, 36dvh, 14rem)` to preserve canvas space.
The shared 44px tab row and necessary run warnings
precede the content scrollport. Evidence uses the shared 12–13px reading scale;
the 11px minimum is reserved for compact rail indicators. Change cards share row heights
without fixed content heights. `--board-playback-height` drives both the dock height and
the canvas container's bottom inset, so fit calculations must not subtract it twice.
Each tab retains its own scrollport beneath the shared tab row. Switching tabs preserves
the terminal height, canvas frame, playback viewport, and each view's scroll position;
it must not trigger a canvas refit. No second transport row consumes the dock.

### Rules

- Hide the terminal with `v-show`, keeping the replay owner and timer mounted.
  Hiding preserves the selected view, selected state, run URL and read-only lock;
  it returns the reserved area to the canvas. Reopening adapts the same frozen
  viewport without restarting playback. Opening a different run starts on Changes.
- The canvas-top playback strip always exposes playback, seeking, position, timeline and Exit
  playback; it adds a restore action while the terminal is hidden. Exit stops the timer, clears the artifact deep link and
  restores the live board and its viewport, including when the terminal is hidden.
  Compact presentation depends on the strip's available width, including when
  expanded sidebars narrow a wide window. Buttons retain 44px targets and accessible
  names. Reopening the terminal focuses its selected tab; closing it focuses the restore action.
  The strip spans the canvas corridor; the tool dock starts below it. Tab icons likewise
  depend on the terminal's available width rather than the outer window width. Compact tabs
  reduce padding, and selection, reopening or a width change reveals the active tab.
  The tab strip stays one row high; its scrollbar does not consume header space.
- Manual seeking pauses autoplay. The shared rail handles pointer scrubbing and
  roving keyboard focus; opening or restoring it reveals the selected state, and
  marker reveal respects reduced motion. Read the rendered
  rail inset for seeking instead of assuming a fixed pixel offset.
  Editing the state-number field pauses immediately and preserves the draft until Enter,
  change or blur commits it. Empty or invalid input restores the selected state number;
  out-of-range values are clamped and the field shows the normalized position.
- Long paths render a width-dependent window of at most 32 numbered markers. Previous/next
  groups and the visible range label provide local navigation; the canvas strip's numeric
  input and marker Home/End keys reach the whole path. Pointer scrubbing addresses the displayed
  window, not an invisible part of the full path. Playback follows the selected window automatically.
  Hide group navigation when the whole path fits. Short paths retain compact marker spacing instead
  of stretching across the canvas. A narrow window may contain only one real state.
  The sequence grid and its track must shrink to the canvas corridor; marker content must not
  expand the measured viewport and cover adjacent Locate or Restore controls after resizing.
- Markers paint at 24px with a 44px pointer target; the scrollport also reserves space for
  the numbered captions. Help badges retain their shared
  20px size. Use the general 44px floor for transport actions, without inflating
  either kind of compact indicator.
- Canvas-strip and terminal actions share one control definition. Icon faces and the seek
  field paint at 28px inside 44px interaction targets; text actions keep at least 4px of
  inner space in compact tabs and 8px otherwise. Hover, primary fill and disabled fill
  stay on the inset face. Tabs use the shared label size and action icons use 16px glyphs.
  History close, refresh and delete reuse the side-panel icon action, including danger
  feedback and disabled treatment. History filters use buttons on roomy screens and one labeled
  select on narrow or short screens. Compact layer tabs omit decorative icons so the first run's
  title and timestamp remain readable before scrolling. Both filter presentations share the same
  selected run type and retain the shared target floor. Active replay and running-tool
  indicators stay inside the tool button border; a paused replay must not keep an expanding
  background animation.
- Plain `HintTooltip` poppers are click-through so a visible step hint cannot
  block transport actions. `InfoTooltip` help remains enterable for longer text.
  Canvas tool panels, verification, exploration and repair result dialogs autofocus their labelled container,
  so opening a surface does not automatically reveal the close button's hint.
  Tab reaches the close control in its normal order; reverse Tab from a modal container
  moves to its last available control without reaching the page behind it.
  Plain hints also open on keyboard focus and close on blur; Enter and Space retain
  the wrapped control's default action.
  Authoring sidebars focus their selected tab when expanded. Template details are a non-modal,
  portaled region: opening or switching a preview focuses its container, Escape returns to the
  corresponding template, and switching editors or collapsing the sidebar dismisses it without
  stealing focus. The narrow-screen focus boundary includes this preview, which stays above
  navigation and authoring sidebars but below modal dialogs.
  Destructive MessageBox confirmations initially focus their content, leaving button selection
  explicit; Enter on that container does not submit the destructive action.
  Closing a simulation error returns to the enabled retry button when its settings remain open.
  If a result temporarily hides its return target, focus restoration retries after rendering and
  leaves any newly focused context alone. Conditional unmount also restores focus after the
  return target renders, even though the destroyed component retains its last visible prop.
  Surface focus records its opener before the originating DOM is removed. A lazy exploration
  result keeps history mounted until the actual dialog reports readiness, including its first load. Results and replays
  opened directly from history return to the history entry when the cached row is no longer visible.
  Playback initially focuses its labelled toolbar without automatically showing a button hint.
  Tab then reaches transport controls. Escape in the toolbar or terminal exits playback, while Escape consumed
  by contextual help only dismisses that help. Playback remains non-modal and does not trap Tab.
  Applying a repair may remove its original entry from the stale verification result. In that case,
  focus returns to the result container rather than a Close button or the page behind it. A negative
  tab index permits this programmatic return without adding the container to the Tab sequence.
  The still-open result carries the apply receipt inline; when no result is open, a toast carries it.
  Clicking Undo or Redo restores that control if still available, or its available opposite when
  history is exhausted in that direction. It does not override focus moved during the request;
  keyboard accelerators retain their existing focus.
- Selection uses accent fill. Violation steps use danger fill and an exclamation mark, with a
  separate ring for the selected violation. The initial state has rounded square paint, an
  accessible Initial label and a heading label while selected; no dummy state changes the stored
  numbering. Name the selected violation above the rail, including a violation in the first or
  only state. Every step in a liveness cycle is marked and named. Keep long violation words in
  the heading and marker tooltip so they cannot overlap adjacent markers. Offer no guessed
  violation location for missing evidence.
- Keep the recorded requirement in Run info. Automation execution records have one home in
  Step changes; a recorded execution is not merely a condition that might match. Device and
  environment deltas have separate headings, with security changes distinguished within device rows.
  State details pairs each recorded state-machine name with its value rather than rendering two
  semicolon-separated lists, omits unobserved readings, and retains stable actual environment values.
  Multi-mode transition evidence names only the state machines whose values changed.
  Complete security facts and compromised links belong there, not in the transition list.
  Devices and identified rules absent from the current board carry a readable caption,
  not a hover-only icon. Missing rule identity does not prove absence. Frozen connections
  still replay independently of current-board membership; lack of delivery animation does
  not prove that a connection is missing, since a compromised link also suppresses delivery.
  A simulation's board-change warning explains that replay uses the saved scene rather
  than requiring a new run to inspect historical evidence.
  The counterexample's checked expression and run-wide scope belong in Run info.
  Simulation information names the executed model and attack budget without implying exhaustive
  specification checking.
  Preserve incomplete-model and unavailable-semantics warnings and identify
  exploration evidence as candidate findings rather than formal conclusions.

## 11. Canvas geometry and lookup ownership

`CanvasBoard.vue` derives every connection layer and label position from the same visual node
geometry. A held drag uses `nodeDragState.tempPosition`; ordinary connections, self-loops, SVG
hitareas and playback flow must all follow it before release. Pointerup commits the node layout
once. Cancellation, lost pointer capture and interaction locking discard the preview. Do not
write committed edge coordinates during a drag: they describe the old position and needlessly
invalidate parent scene watchers. Pointer resize and keyboard resize use the current node dimensions.
Drag samples share one pending animation frame; each frame uses the latest sample. Pointerup
uses a newer release position even when no corresponding pointermove was delivered, then flushes
an unpainted sample before committing. Resizing likewise includes a newer release position.
A release at the last sampled coordinates reuses that sample instead of reinterpreting it after a
zoom change. Cancellation, lost capture, locking and unmount discard previews and cancel the frame,
including previews already painted. Each queued sample retains the zoom at receipt.
Hover changes a node's elevation without translating its box away from these anchors. Opaque node
surfaces need no per-node backdrop blur.
The canvas grid writes its pan and zoom directly to the root background size and position. These
properties do not inherit into node content; inherited custom properties would invalidate the
whole node subtree on every pan before live geometry reads flush its styles.
`components/__tests__/CanvasBoard.spec.ts` covers these transitions at multiple zoom levels and
`e2e/canvas-runtime-environment.spec.ts` covers held dragging, persisted layout, reload and undo
against the real backend.

Server node snapshots retain equal validated JSON fields and reuse the existing node collection
when its values, membership and order are unchanged. A layout acknowledgement therefore keeps
unchanged runtime collections and model presentation caches stable. Actual server changes still
propagate, while active pointer layouts and newer pending layouts retain ownership. Synchronous
model-fingerprint and exploration-preview invalidation remains immediate for semantic changes;
it must not repeatedly serialize the whole board for equal per-node runtime arrays.

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

Node content records track their actual display inputs through Vue computed dependencies,
including nested runtime values, template data, locale and reactive values read by display callbacks.
Separate presentation records add geometry, density, focus and interaction state, so zooming and
resizing do not repeat unchanged semantic formatting or icon selection. Position stays outside
both records. The node loop memoizes each subtree by its presentation record and visual coordinates,
so a held drag does not rebuild unchanged node content or repeat its formatting.
The loop still compares O(N) memo entries and computes O(E) connection geometry per painted drag
frame; it does not claim constant-time rendering. Runtime edits, playback transitions, resizing,
focus and locale changes invalidate the affected presentation inputs normally.
Content counter-scaling clamps before writing its inherited style value, so zooming above full size
does not invalidate an already unchanged scale. The scale and label-line limit are scoped to card
content, so changes below full size do not inherit into sibling resize handles or node decorations.
Node, content and resize-handle declaration blocks use CSS text so Vue can skip equal styles and
apply changed declarations together, rather than writing each unchanged property during zoom.
Resize handles use direct width, height and negative margins at zero-offset corner anchors.
Their artwork occupies a fixed fraction of the hit box,
preserving screen-space geometry without inherited resize variables restyling their pseudo-elements.

Automation connections keep a solid resting stroke; delivery pulses are a separate layer.
The SVG viewport translates for panning and applies zoom inside it to preserve non-scaling strokes;
HTML nodes apply the same coordinates in a sibling layer. Both world layers keep their transforms
eligible for compositing during panning. Empty HTML space passes pointer events through to the SVG
hit regions.
The minimap, edge popovers and native zoom-input constraint use geometry captured after layout.
Node bounds, panel state, shell insets and window dimensions refresh that capture; a border-box
ResizeObserver also covers actual canvas-container size changes and disconnects on unmount.
Navigation commands still measure live geometry. A zoom command shares one freshly measured frame
across its fitted minimum, clamping, world-center calculation and new pan. Rendering capture likewise
uses the same frame for its published geometry and input constraint. Do not cache navigation frames
across commands: resized windows, panels and playback insets must take effect on the next input.
Pan and zoom do not resize the frame, so their
rendering must not remeasure it or the popover viewport between node-style writes.
Both world layers are absolutely positioned at the same canvas origin. Navigation padding reserves
visible space without shifting either layer. Fit and map calculations use the actual free space below
the navigation and playback-strip inset. The dock already reduces the canvas container height;
fit calculations do not subtract a second overlay height. A fully covered canvas has no visible frame.
The narrow/short-window Fit action sits below the same playback-strip inset and shares the ordinary
action radius and surface. The transport must not intercept its pointer target or turn Fit into seeking.
Fit padding is at most one eighth of the available frame on each side, capped at 72px.
Narrow and short overviews therefore use at least three quarters of their limiting dimension
unless the maximum zoom is reached; padding must not squeeze the diagram into half its frame.
Frozen playback starts in a fitted overview. While in overview, terminal visibility, window size,
sidebar collapse/width and tool-dock width changes refit it. Manual zoom or pan retains its
scale and viewed world center through those changes. Fit-to-content resumes automatic fitting;
opening another frozen scene starts with a fresh overview. Playback navigation is transient.
Closing playback returns to the live board's pan and zoom.
Control-wheel navigation ignores events with zero vertical displacement, including horizontal
scrolls and coalesced opposite vertical samples. Nonzero displacement retains the ordinary zoom
step, bounds and viewed world center.
Reverse connections shift the line perpendicular to its
direction before clipping it to both node boundaries; endpoints are never shifted off the nodes.
Stroke and pointer-hit width do not scale with canvas zoom. Direction markers use explicit user-space
viewports. Rule details sit in a separate HTML layer at a constant screen size. A pointer hover opens
the card after 400 ms, beside the latest pointer position, then holds it steady. Leaving, changing edges,
removing the edge or pressing Escape cancels a pending hint. A crowded edge flips the card left.
Keyboard focus opens it immediately beside the connection. Cards stay inside the same available canvas
frame used by fit/map calculations, including
sidebars, the action dock and playback. Content wraps without ellipsis and scrolls within a bounded
height. A short pointer bridge lets users enter the card to read or select text; Escape dismisses it.
The details include every simultaneous rule condition, the command and its selected content-label
source, from the live rule set or the frozen playback rules respectively.
These are rendering dimensions; node positions, rule identity and saved scene geometry are unchanged.
The Step changes tab owns full previous/current pairs; compact node chips show the current value.

### Device cards allocate readable screen space

Names use the full grid cell rather than a fixed world-space width or an absolute bottom-corner
badge. The previous 9rem cap became 29px at 20% zoom even on a 120px-wide card, truncating
`Air Purifier` while most of the card stayed empty. Artwork and names occupy separate cells;
wide compact/medium cards use a horizontal arrangement, and other shapes keep a centered group.
Names wrap to two lines in medium/expanded cards. They use ordinary theme text without a
separate badge surface, border or shadow.

Density follows the rendered footprint. Stacked compact cards retain artwork and up to two name lines,
or three when their screen height reaches 60px; the icon row yields space to those name lines.
Cards below 48px in screen height prioritize up to two name lines over artwork; below 32px
they use one line. Micro overviews show artwork when even a readable word cannot fit.
The full name remains in the node title and accessible label. Medium cards also show the current
state; expanded cards add wrapping runtime readings and security labels. Variable labels yield
width before their values. Typography follows [the screen-size rule](#a-clamp-whose-middle-term-can-never-win-is-a-fixed-size-wearing-responsive-syntax).

Card layout lives in `board.css`; `CanvasBoard.vue` owns density selection and state semantics.
The card uses the shared action radius and palette surface. Its content clips inside that shape,
while the attack marker is a sibling outside the clipped content. Saved node dimensions and
positions remain the geometry used by connections and resize handles. Real browser coverage in
`canvas-runtime-environment.spec.ts` checks complete names where the footprint has room, and
two-line ellipsis with full-name tooltips and accessible labels on constrained compact cards.
It also checks text size, containment and icon/name separation across shapes, zoom levels and themes.

Expanded cards center their content within a maximum 30rem screen-space column. Enlarging the
card gives its content room without separating the artwork, name, readings, and security labels
across the entire canvas. The existing persisted size limits remain unchanged.

Every unlocked node retains a bottom-right resize grip, including short, narrow, and micro
overviews. Its hit area stays 44 screen pixels at every zoom and moves outward on small nodes,
leaving most of the node body available for dragging. Hover, keyboard focus, and selection reveal
the grip. The other three grips appear only when both screen dimensions can accommodate them.

## 12. Public pages and workspace share a visual language

Welcome, authentication and the canvas use the same theme-aware surfaces, neutral text,
palette accent and font tokens. `ProductMark.vue` owns the topology mark; both headers use the
same display font and wordmark weight. Edge-attached navigation uses an opaque surface and
hairline border rather than a floating shadow.

`styles/base.css` owns shared spacing, text-size, target-size, header-height, page-gutter,
transition-duration and fill-ink tokens. Public pages and workspace navigation consume
these roles instead of redeclaring equivalent numbers. Canvas and illustrative topology geometry
remain local; their coordinates are not interface spacing.

`styles/header-controls.css` owns theme/language control geometry, states and focus rings
for both headers and the narrow-screen overflow menu. Controls have a 44px target height;
the compact language control needs at least 88px for its icon and complete `中 · EN` pair.
Headers must not override that width or add per-page shadows. Workspace navigation uses
the same action radius and quiet hover surfaces.

`ThemeToggle.vue` opens the shared non-modal appearance picker instead of cycling through hidden
choices. Light, dark and system modes are explicit; copper and monochrome palettes are independent.
`useTheme.ts` owns the two local preferences and applies them before mounting. Resetting to system mode
keeps the selected palette. The picker uses a bounded, teleported surface; Escape restores the trigger,
leaving focus or clicking outside closes it, and scroll/resize updates its position.

`TopologyShowcase.vue` presents the same device artwork, node palette helpers, state chips, directed
rule connections and canvas grid as the workspace, with a shallow perspective rather than floating
nodes detached from their connections. Its three-device illustration projects the porch-motion,
light and door-lock rules from the [away-mode unlock scene](away-mode-unlock-demo.md).
R1/R2 labels correspond to the two displayed automations. Nobody-home context and a preset three-step
path show how motion can unlock the door despite the occupancy safety specification. This is an
illustration, not a live verification or the scene's exact recorded trace.

The preset opens on its counterexample. Replay first restores the initial readings and artwork, then
advances the motion reading and the delivered light/unlock actions. Device changes, rule flow, selected
step and violation feedback follow one selected step. Playback is finite and can be paused, resumed or
sought with previous/next controls or step buttons. The compact view retains state controls and the
illustration disclaimer. Reduced motion removes tilt and flow animation while retaining every step.
Node positions and rule endpoints share one layout. A resize observer measures the world before its
perspective transform, so bends and arrowheads keep their proportions across container sizes. Node cards
reserve space for two-line labels and readings; replay does not change their footprint.
`styles/device-motion.css` owns artwork and reading transitions for both this example and the workspace.
Readings cross-fade in one grid cell without an empty phase; reduced motion removes those transitions.
Mouse tilt uses time-based damping and stops requesting frames once settled. Unmount releases the
playback timer, resize observer, scheduled frames and preference listener; there is no idle animation loop
or WebGL runtime.

Narrow authentication stacks in document flow and omits the decorative topology so the
form remains easy to reach. Registration and errors can scroll. Theme/language controls
remain available; the 404 surface uses the same header, canvas ground and panel treatment.
Authentication progress retains a slowed spinner under reduced motion.
Public copy uses the workspace's terminology for safety specifications and workspace entry.
The AI assistant uses the shared body font, opaque panel surface and elevation roles;
warning text and filled recovery actions follow the same semantic color tokens.
Escape within its non-modal panel closes it and restores the opener's focus; hiding the
panel does not stop an active response. Escape outside it does not dismiss it.
The bottom composer grows upward from a handle above its text field, rather than downward from
a native bottom-corner resize grip. Up/Down arrows adjust its height; Home/End choose its bounded
minimum/maximum. The handle exposes its current height and instructions in the active language.
Resizing preserves the draft and does not submit a request; viewport changes clamp the height.
Opening the assistant remains available when Board data is loading or failed; sending stays locked
until authoritative Board data and any pending reconciliation are ready. A descendant component
failure uses `AssistantRecoveryPanel` above the canvas rather than a fallback below the full-height
Board. It can be hidden, reopened, or retried without clearing persisted conversations. Remounting
requires authoritative reconciliation before further assistant work; retry alone cannot clear that
guard. `App.spec.ts` and `chat-assistant.spec.ts` cover recovery, focus and narrow/short viewports.
Before the assistant first mounts, timer and focus refreshes share one in-flight session observation.
Further refresh demand schedules one follow-up after that observation and its reconciliation settle,
so slow responses remain usable. Account changes, public navigation and unmount discard the old
refresh owner and its queued work; the mounted assistant continues to own its own session monitor.
Drag and resize gestures start from the current window geometry, never an interpolated bounding box
from the opening or viewport animation. `ChatView.spec.ts` covers both gestures during that transition.

## 13. Workspace shell and concise copy

`styles/board.css` owns both side-panel headers, collapse controls, section tabs and count badges.
They consume the shared spacing, target, radius, type and motion tokens. Selected navigation uses
an elevated neutral surface and accent ink; filled accent remains available for primary actions.
Collapse controls face the canvas edge and point toward the side they close.
Their localized tooltip and accessible name identify the affected panel in both expanded and
collapsed states, so the two controls remain distinguishable when the headings are hidden.

The collapse toggle remains mounted to preserve keyboard focus. Content fades before the rail
finishes narrowing and keeps its expanded width during the transition; leaving controls are inert.
Both sides use the same panel duration and easing, with motion disabled under reduced motion.
On narrow screens, a panel opened during initial loading stays open when the saved layout arrives.
This temporary overlay does not change the saved desktop layout.
Likewise, manual canvas pan or zoom during loading takes precedence over the initial narrow-screen
fit; automatic fitting applies only while the user has not moved the viewport.

Section navigation stays outside each panel's scrolling body. Counts sit below section names.
Both panels keep their four tabs in one row, omit decorative tab icons,
and use the same short section names. Shared CSS owns the four columns and distributes width by
label. Tab text keeps inner spacing from selected and hover surfaces; narrow groups release
inter-tab gaps and outer inline padding to preserve that spacing. Long labels wrap within their own cell; section headings wrap,
and form grids follow their available width. Narrow template cards may shrink below their preferred
width; the template toolbar wraps its action group when the title and controls cannot fit together.
Entity names and rule content wrap without competing with actions.
The inspector has four peer tabs: Devices, Rules, Specifications, and Environment. The last tab
uses the existing layout authority, so its desktop selection survives a reload; narrow overlays preserve
the saved desktop layout. Each tab has a compact toolbar and search field; the extra accordion around a selected inventory is removed. Shared
`board-inventory-card` styling also owns the Control Center template cards: neutral surfaces,
accent borders and a quiet raised shadow on hover or focus, with no positional motion.
Expandable section headers use shared padding, icon scale and hover surface.

Device rows put the full name above template and modeled state. Rule rows separate conditions
from the target action and its content source; rule-priority controls sit below the content,
preserving the real order while filtering. Specifications lead with their temporal template and
natural-language property, retain explicit variable-source labels, and disclose the formula on demand.
Device-list import has a labeled text input and a keyboard-accessible file button. Detailed format
help remains in its help tooltip. Import errors and automatic name adjustments wrap onto their own
line with the device name; essential correction guidance must not depend on a native hover title.
List mutations use `board-panel-icon-action`: a 28px painted face centered in a 44px target.
The target occupies its own space, never overhangs a neighbour, keeps an opaque disabled state,
and shows a shared keyboard ring. Delete actions remain visible, with danger colour on enabled hover.
The device-instance heading and Save action stay in normal document flow, separated from the
fields below. A sticky section heading covers field labels and controls as the dialog scrolls.
Keep the short blocking reason with its disabled Save action.

Environment rows show the initial value, domain, effective shared labels, and separate reader/writer
counts. Expanding a row exposes its existing value editor and device-navigation links, grouped by
read or affect capability. Evolution and advanced label edits are separate disclosures. Search
matches variable names, values, labels and related devices. The panel explains that a run starts
from this value and does not write its trace back; model ownership and evolution remain defined by
[shared value semantics](../architecture/shared-value-semantics.md). Validation and unavailable
data stay inline rather than implying an empty or successfully saved pool. A focused field's
diagnostic scrolls into view without moving focus; inactive fields cannot move the user's scroll position.

The map stays in the inspector's bottom dock, outside the inventory scroll region. Selected
inventories begin directly below the tabs; switching tabs returns the inventory to its heading.
The overview is a keyboard-operable disclosure, initially expanded at 600px viewport height
or above and collapsed in short viewports. The dock is height-limited and scrolls when an
expanded overview would otherwise crowd out the inventory. Zoom controls keep full targets
and share one row with the compact heading. The full "Canvas overview" name remains accessible.
The heading reserves inner space for its text and chevron. Its hover surface, zoom icons and
zoom field share the 28px quiet face height while preserving 44px interaction targets. Redundant card
and toolbar shells are omitted so the map receives most of the dock's area. Only exceptionally
narrow containers wrap controls; the working map grows with viewport height. The field receives
a visible focus-within ring. Floating workflow headers use compact vertical spacing instead
of full card padding, leaving more room for forms and results.
Device-list import keeps JSON and CSV as separate sub-options under Import. Only the selected
format's file filter, paste prompt, help and download are shown; each draft is retained independently.
An explicit input-method radio group exposes either file selection or the pasted-text editor, never
both. A selected file is identified by name and can be removed without discarding the pasted draft.
Both formats offer equivalent initial configuration and share validation and targeted append.
The [device-list format contract](../api/board.md#device-list-input-formats) owns the field mapping
and supported encodings. CSV exposes a file-encoding selector with contextual help; decoding failures
explain how to correct the selection or save an appropriate file before retrying.
Problem rows retain their parsed device name and show an entry/line location. Shared-variable
conflicts share one alert with concise field reasons; one linked blocking notice explains that
the complete batch must be corrected. Rename warnings describe a plan until import is confirmed.
Returning focus to the device importer dismisses earlier success/info toasts before the next
edit or tooltip opens; errors and warnings remain visible.
The empty textarea carries an instruction rather than sample data. Starter files remain separate download assets rather
than inlined data URLs. Template details show every declared item inside
the scrollable preview, wrapping long names instead of hiding entries behind inert overflow counters.
Rule cards group trigger conditions and the action to execute in separate bordered sections,
using the editor's terminology. Each required condition has its own line; the command pairs
the target device with an action chip. Content-label sources and order controls stay separate.
Labels above editor fields use block layout so their vertical spacing applies. Device variable
headings let the name and default-value hint wrap independently in creation, instance editing and
template-drop dialogs; secondary hints must not squeeze the name out of view. Specification condition
validation occupies a full footer row above Cancel and Add/Update. Its source explanation describes
the difference between the choices rather than repeating the blocking selection instruction.
Both side panels use the English short label **Specs** so the tab stays on one line at supported widths.
The empty canvas uses shared action
styles and a scroll region with safe centering so short viewports can reach every entry point.
Fit-to-content places every node inside the canvas frame, including narrow and short frames.
Narrow side panels overlay the stage; map navigation and fitting use the underlying rail
frame so an open panel does not invalidate the available canvas. Its overview may go below
the ordinary manual zoom floor; manual zoom can return to that overview, and the saved layout
preserves it. Panel tabs retain their shared text size and full interaction targets. Below 640 CSS pixels the header
groups preferences and the single account entry into its overflow menu so all actions remain reachable.

Saved specification cards use the localized requirement itself as their heading. The editor and
inspector share `utils/modelPresentation.ts` for condition wording and temporal meaning; rules and
canvas details also share their condition presentation. Bundled tokens follow the interface language;
custom tokens retain their authored spelling. Actual, reported and unresolved readings remain explicit.
The inspector labels the requirement body **Specification description**, matching the editor and
keeping it distinct from the optional formula preview.
Formula previews are collapsed by default in saved cards and are available through a keyboard-operable
disclosure. CTL/LTL is a separate badge; the expression comes from current conditions, never a saved
display cache. The editor likewise separates the language badge from the formula expression.
Condition fields have associated visible labels. An empty condition asks the author to select
a device; only a previously selected missing device is shown as deleted. Exploration results
and history identify targets by their frozen structured conditions, and use recorded token
provenance for localization.

Verification results share the inspector's requirement wording, formula disclosure, source chips,
and CTL/LTL badge. Their presentation comes from the recorded result snapshot rather than current
Board entities; [the verification contract](../api/verification.md#specificationsnapshotdto) owns
its fields and the explicit missing-interpretation state for older records.

Translation checks walk production source, both locale catalogs, and finite dynamic key families.
Tests cannot make an unused message necessary. Catalogs must have equal keys and interpolation
parameters, valid message syntax, and no duplicate definitions. Confirmation copy keeps affected
counts and consequences while omitting repeated instructions and internal implementation detail.

Workflow headers use the same neutral surface and title/subtitle roles, including simulation and
recommendations. Never force all children of a floating panel's first wrapper to white; such a rule
overrides the header's explicit text roles and hides them on light surfaces.

Help text states the modeling rule or next action directly. Keep provenance versus compromise,
sensitivity versus access control, actual versus reported values, and formal versus candidate
evidence explicit. Remove review history from the locale catalog. A conditional tooltip mounts only
when its trigger exists; an empty trigger is a component error, not useful hidden help.

Verified against code on 2026-10-16 for copy semantics in `assets/i18n.ts`,
`utils/modelPresentation.ts`, `utils/traceView.ts`, and the backend model builders.
Use “rule priority” for order controls, “candidate counterexample” for exploration evidence,
“Counterexample Exploration” for the English workflow name, and “compromised” for the modeled
device state. Navigation and lists use the full English “Specifications” label.
Template definitions are “device templates”
throughout their import, reset, and deletion flows. Temporal descriptions talk about conditions
holding; current-state satisfaction remains explicit for eventual responses. The formulas and
propagation rules belong to [specification templates](../architecture/spec-templates.md) and
[shared value semantics](../architecture/shared-value-semantics.md), not separate UI definitions.
Specification security-label values follow the interface language in choices, drafts and saved
explanations, including custom templates; persisted enum values remain canonical. Condition previews
and draft rows name the source or sensitivity label explicitly rather than presenting it as a device state value. Bundled playback
tokens whose spaces were removed by NuSMV share the manifest spelling's translation. Custom and
unknown-provenance identifiers remain unchanged.
Result copy names the checked model and preserves partial evidence and omissions; a satisfied
verdict cannot imply every real-world behavior is covered. Fix copy identifies the target and
preserved requirements, following [automatic fix semantics](../architecture/auto-fix.md), rather
than promising that no other problem exists. Rule removal must not read as irreversible; the
[undo rules](#3-undo-and-what-it-is-not) own recovery behavior. Session-expiry
notices distinguish saved data from unsaved edits. Routine help omits implementation provenance
and repeated guarantees; unknown mutation outcomes retain reconciliation and next actions.
Login and registration focus on the form; the illustrative topology remains in the product overview.
Pending authentication and workspace-navigation recovery reject repeated form submissions. A successful
authentication response must contain a usable credential and canonical account identity before the
workspace adopts it; an unusable registration response retains the unconfirmed-outcome guidance.
Browser navigation that changes the authentication mode or return address abandons the previous form's
request ownership and releases its busy state. A late response cannot sign in, navigate, report an error
on the replacement form, or release a newer request's busy state. This does not cancel server-side account
creation. `Landing.spec.ts` checks Back navigation and language changes for both forms.
Registration conflicts appear at the affected input, while raw account-service diagnostics use a
closed technical disclosure. Account-input names reference their visible labels, with requirements
and validation in separate accessible descriptions. Validation and account-request failures retain
message keys or structured errors so language changes update existing feedback without clearing drafts
or resubmitting. Login transport failures do not blame credentials; only a credential rejection does.
An unconfirmed registration asks the user to try signing in before registering again.
Profile-load failures own the disabled-editor explanation instead of repeating it inside each form.
Profile reads, rename reconciliation and rejected username/password changes retain structured diagnostics
in one closed disclosure. Stable rejection codes choose localized copy; other service prose does not
replace the localized summary. A completed profile refresh after an uncertain rename is a warning to
review the authoritative value, not a failed save or a success receipt. Capacity feedback names the
account service because the same limiter presentation covers sign-in, registration and account changes.
Sign-out and permanent-deletion buttons keep their localized action names and expose busy state while
pending. The compact return-to-workspace control uses the shared pointer and keyboard hint.
Long login and global render-error diagnostics
stay within the available width; the global fallback keeps its diagnostic disclosure in a bounded,
centered reading column and preserves structured field diagnostics. A captured async-component loader failure
offers document reload rather than render retry, because a cached failed module import can fail again after
the canvas remounts. Ordinary render failures retain their in-place retry. An unconfirmed account deletion keeps its recovery
notice on the login destination through input edits; a temporary toast cannot own that uncertainty. Username changes
confirm success through the displayed authoritative value. Inspector and canvas overview empty states distinguish a
pending or failed snapshot from a confirmed empty collection. Account deletion names permanent loss
and accurately limits scene export to the scene and its referenced templates.
Run-history empty states name the selected result type; offer the all-results view when another
type has saved history. The all-results guidance qualifies every run type as saved, including verification
and exploration. Model comparison says it is loading while fingerprints are being read; unavailable
describes a settled read without usable comparison data. Background-task lifecycle guidance lives in
contextual help beside its summary rather than repeating below an empty list. English quantities use plural forms or labeled
counts. The workspace loading strip reserves vertical space above side-panel headers.
History and task-list read failures retain diagnostics, with localized summaries rendered in the current
language and raw causes and fields kept in one collapsed technical disclosure. Failed reads do not imply
an empty collection. Warn about stale results only when a failed source still has retained rows;
unrelated successfully loaded results do not earn that warning. History action locks identify a pending
deletion separately from playback. Historical result openers use localized failure copy and log service diagnostics.
Environment initial-value guidance appears only when there are values to edit; conceptual help
remains attached to the section heading.
Device creation and details use the same device-template and device-name labels. Rule and
specification comparisons share field wording; numeric condition values receive the same integer
domain checks as initial values, following [board validation](../api/board.md). Set comparisons
show comma-separated input guidance. Numeric value errors block adding a condition and hide its
incomplete preview.
Rule creation names the current missing field once and links disabled actions to that explanation.
Duplicate precheck feedback retains the matched rule and formats its explanation in the current
language; the generic rejection headline is not repeated as a second cause.
The optional rule name states automatic generation when blank. Device renaming has a visible input
label, inline duplicate-name feedback, and an explanation for empty or unchanged names.
Selection prompts distinguish choosing a value from typing one. Library confirmations retain their
icon styles and cap their width to the viewport; close controls keep full pointer targets. Device
dialog actions use short visible labels with complete accessible names.
Run settings distinguish simulation transitions from exploration path-state counts; step help explains
the initial state and early termination, following the [run contract](../api/verification.md).
Simulation and verification share run-mode labels; background simulation explains automatic history
saving. Saved-result notifications apply to either execution mode. Failed verification requests have
an error heading and tone, separate from completed inconclusive results. Disabled run controls link
to inline explanations without duplicating them in tooltips. Desktop parameter panels allow room
for English option labels and retain their compact viewport limits.
Run failures retain message keys and raw diagnostics rather than translated sentences. Their
summary, task-limit counts and next step follow language changes; raw service text and field errors
remain in one technical disclosure. A generic failure heading is not repeated in the body.
Completed tasks with unreadable results direct users to reopen history rather than rerun. Exhausting
status polling means tracking stopped, not that execution timed out. Resuming a task clears its old
failure, and resuming exploration also clears the previous settings or recovery notice.
An unconfirmed cancellation restores the latest known phase without replacing a newer poll response.
A rejected cancellation with a confirmed terminal status displays that status immediately.
Watching a task from history opens its owning progress panel. The compact task indicator is shown
when workflow panels are closed, so it does not repeat their task controls. While an asynchronous
verification or simulation is running, the panel shows its submission snapshot rather than the
current settings draft. Simulation steps, compromise assumptions and sensitivity propagation come
from the accepted task. A pending cancellation remains visible even when a poll updates the phase.
Cancellation confirmations use the visible task status in the inbox or progress panel; the canvas
receives one toast when those panels are closed. Polling the same local cancellation adds no second
toast. Cancellation observed from another session still receives a notification.
A late cancellation response that confirms completion adds no notice over the matching visible
verification result or simulation trajectory. Results belonging to another task do not suppress it.
Simulation completion opens its trajectory without an additional completion toast over the timeline.
Deferred results still notify users when their trajectory is not shown.
Exploration seed help qualifies reproducibility by model, settings, and the time limit. Candidate-free
targets are listed without repeating the whole-run proof boundary on every row. Sub-second exploration
durations display as less than one second in both results and history rather than rounding down to zero.
Exploration progress keeps its percentage and contextual budget-versus-coverage help together;
the status can wrap independently without squeezing those controls.
Restoring exploration settings preserves the historical target scope across language changes;
its notice follows the current language. Recovery feedback stays in a visible exploration panel
without a duplicate toast, while a hidden panel still permits a notification.
An unconfirmed history save asks users to refresh and review history before rerunning; it must not
claim that the asynchronous refresh has already succeeded.
Optional descriptions render only when the current manifest supplies text. State/content tables
omit a description column when every entry is blank; clearing a description must also clear its
display, without falling back to a cached Board dialog field. API cards have no Trigger field:
the [template contract](../api/board.md) reserves structured triggers for internal transitions.
Source-choice help has one owner and describes the selected variable's falsification capability.
Single-device value validation reveals the edited field's error with nearest scrolling after render,
without moving input focus. Valid values and changes to an unfocused field do not scroll the panel.
“Environment value” means the shared model value, not a live measurement. State effects describe
next-step updates, following the [model timing](../architecture/nusmv-model.md); they must not imply
an invariant that a state and its reported variable agree in every frame. Generic assistant presets
refer to the current model and available templates rather than inventing devices or transport links.
Draft previews require complete fields and current device references. Empty specification groups
must not display `AG(TRUE)`, and condition previews must not substitute the word “value” for an
unconfigured value. Rule choices label shared readings as device-reported values. Template summaries
use “actions”; `APIs` remains the manifest field name in the owning contract.
Simulation's manual history-save control appears only in synchronous mode; the background mode's
label states automatic saving. Workload arithmetic and its model-complexity factor live in advanced
settings, while the exploration footer shows the workload and limit. A rejected estimate has one detailed
configuration explanation in the scrolling body instead of a normal workload summary.
Result requirements describe obligations rather than imply satisfaction. A shared stale banner
replaces identical per-counterexample notices. Execution logs are collapsed for ordinary verification
outcomes and open for incomplete or inconclusive models; solver output remains available separately.
An empty exploration result retains its undecided outcome and formal-verification action without an
empty findings section or finding-specific repair advice. Element Plus built-in controls derive their
locale from the same Vue I18n state as application copy. The Board heading names the product; its
logo button separately names the view-reset action.
Exploration results put candidate evidence and per-target outcomes before counts and run configuration.
Model comparison failures or changes and omitted-target reasons stay inline before the evidence.
Reproduction parameters, snapshot counts, and detailed capability boundaries use native disclosures;
the settings-reuse action remains outside the disclosure, and the run seed appears only with its
configuration. Requirement labels wrap rather than rely on native title hints to reveal truncated
conditions. Narrow layouts wrap candidate actions inside their cards and place the settings-reuse
action below the reproduction heading. The chosen exploration mode remains visible, with its
conceptual explanation in the shared `InfoTooltip`. Rule conjunction and content-label explanations
likewise annotate their headings;
exploration target help annotates target selection. These help controls support pointer, keyboard,
and touch interaction. Unavailable candidate evidence explains its disabled actions inline.
Exploration input choices and capability details follow the target and budget controls and expand
on demand; range loading and failures remain visible. Candidate cards own found targets, while a
separate list owns targets without candidates. An early time limit stays inline for both finding
and empty outcomes and must not be described as consuming the complete iteration budget.
History uses the same time-limit distinction. Its exploration-mode and model-comparison help uses
the shared keyboard- and touch-operable `InfoTooltip`; active-task model changes remain inline.
Exploration summary metrics stack on narrow screens and use three columns from the shared `sm`
breakpoint, with wrapping values in both layouts.
Scene-import confirmations compare current and incoming counts in a table and fit narrow viewports.
Irreversible replacement and the exact undo-history loss stay visible; template retention and
rule-order handling expand
separately. Template import uses a keyboard-operable button, with format and repository guidance in
contextual help. Template loading describes a read, not default initialization. Reset summaries count
processed templates without claiming all definitions changed; blocked deletion shows the referencing
devices with wrapping names instead of an irrelevant irreversibility notice.
Contextual help's enlarged pointer target must leave adjacent actions clickable; check both locales
because longer text can wrap the controls and conceal an overlap in the other locale.
Verification mode explanations have one visible owner beside the mode controls, without repeating
the same sentence in a hover hint.
Toolbar layout switches keep accessible names without hover hints. Functional tools retain their
explanations; a plain-text hint closes after leaving its control, even when the pointer crosses the popper.

Device-detail concepts annotate their section headings with `InfoTooltip`; runtime validation and
draft conflicts remain inline. Stateless devices show one capability explanation, without empty
mode, initial-state, or state-table rows. The basic table labels the template's initial state explicitly;
instance configuration owns the starting values used by runs. Device-linked specifications point to
the System Inspector's create/delete controls, rather than promise an unsupported saved-spec editor.
Specification comparison fields appear after selecting a device. Template explanations remain fully
readable. Device-linked requirements reuse the same natural-language description, and keep the CTL/LTL
badge separate from the formula expression. Technical formulas expand independently of the requirement
and save action. History omits ordinary complete-model simulation badges while retaining incomplete-model
warnings. Its result filters use the short noun “Exploration” while retaining the full counterexample
exploration name for assistive technology. Its machine-readable dates match the displayed run timestamp. Failed-task settings actions
open settings for review; they do not submit a run. Candidate-history verification names the current
board and explains that it neither imports the historical scene nor verifies only the selected finding.
Assistant presets focus their populated draft without creating a conversation or sending a request.
Confirmation copy names the authorized write and presents its target separately; decision messages
retain both so the conversation records what was confirmed or cancelled. Preview-capable tool names
must not make a write confirmation sound like another preview.

Verification results place counterexample actions and per-requirement outcomes before the run-level
model download. Recommendation cards show the actual rule or requirement before optional AI rationale;
generation messages and accounting use closed disclosures after the candidate list. Help beside the
request heading explains generation inputs; the unverified-candidate notice remains visible beside results. Scene readiness is an
informational state, not verification evidence. Repair previews show verified changes before optional
search-range controls. Repair scope and range guidance use heading help;
inapplicable strategies name their reason. Numeric targets show frozen rule order and device names
using the [fix response metadata](../api/verification.md#5-auto-fix). Related-rule rows retain conflicts
without repeating the transition, device, and action already displayed. Teleported help uses the
shared tooltip layer above panels and nested modals, below confirmation dialogs.

The user center is a full private settings page with concise headings and settings rows.
Initially show the current username and login phone, with explicit edit actions; internal
account ids and registration metadata are not user settings. Do not add numbered sections,
decorative identity panels, navigation for a handful of settings, or unimplemented options.
Username editing contains only the new name. Continuing opens an identity confirmation
using the shared modal layer; its current-password field verifies the requested change
rather than appearing as account information. The password editor appears only after its
own edit action. Cancelling or switching editors clears password values.
Sign-out is available within the same page with its two-answer confirmation. Permanent
deletion remains a clearly separated destructive action with its own confirmation form.
Password fields support password managers and pasting, with current/new-password autocomplete
and a confirmation field. A password constraint appears at its field once; when it also
blocks submission, the action references that same guidance through `aria-describedby`.
Other blocked reasons remain visible beside the action. Account values and credential-change effects belong to the
[auth contract](../api/auth.md).
The page uses ordinary document scrolling and focus navigation; only the identity,
sign-out, and deletion confirmations claim dialog roles and trap focus. At narrow widths,
settings and editors stack without reducing action targets. Returning restores the complete
Board artifact URL; edit-state ownership, account return, and route settlement belong to the
[frontend integration guide](frontend-integration.md#auth--apiauthts).

Tool headers, mode labels, input-domain previews and ordinary counts use neutral roles. Accent marks
selection, primary actions and changed values; danger, warning and success describe consequences or
evidence. `info` aliases the accent palette. Run history keeps outcome badges and diagnostic warnings
distinct while its metadata and secondary actions stay neutral. Fix previews use neutral sections
and before/after values rather than a different background colour for each adjustment category.
