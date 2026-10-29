# CLAUDE.md — IoT-Verify Frontend

Guidance for Claude Code when working in `frontend/`. Read the repo-root
[../CLAUDE.md](../CLAUDE.md) first for cross-cutting rules (doc-sync, language, git).
Detailed reference lives in [../docs/](../docs/README.md); when code and docs disagree,
**code wins — fix the doc in the same change**.

## What this is

Vue 3 + TypeScript single-page app (Vite) for the IoT-Verify platform. What it is and how it fits
the backend: [../docs/architecture/overview.md](../docs/architecture/overview.md); the stack is in
`package.json`. UI components use Element Plus with Ant Design icons; match the neighbouring
component's conventions.

This project is in active development and has no released compatibility contract.
Unless the user explicitly requests a migration path, remove superseded client contracts
and dead UI branches instead of adding legacy payload adapters, deprecated aliases, or
silent fallbacks for old development data.

## Commands

```bash
npm install
npm run dev        # dev server :3000, proxies /api → http://localhost:8080
npm run build      # vue-tsc type-check + production build (run this to verify)
npm run preview
npm run test:unit  # Vitest
npm run test:e2e       # critical smoke; needs the test backend
npm run test:e2e:full  # complete non-live browser regression suite
npm run test:e2e:live  # explicitly enabled external-provider tests
```

Delegate browser runs to a background subagent (see the root CLAUDE.md) instead of blocking on
them, and keep reading source while they run. Test selection and local execution are owned by
[../docs/development/ci.md](../docs/development/ci.md); use the full runner only when its broader
coverage is needed.

**Four things will make an E2E run lie to you, and all four read as product regressions.** A full
pass needs a second backend with raised auth rate limits (the defaults cannot carry the suite); port
3000 must be free, because `reuseExistingServer` is off so a leftover dev server cannot be silently
adopted and tested as stale code; serving the build yourself on a port outside `CORS_ORIGINS`
fails every auth test with a 403; and two known-flaky specs have documented non-product causes. The
recipe, the measurements and the diagnosis order:
[../docs/development/known-traps.md](../docs/development/known-traps.md#3-e2e-environment). **Read the `reasonCode` or
the Playwright trace before believing any E2E failure.**

## Conventions (hard rules)

Directory layout: [../docs/architecture/overview.md](../docs/architecture/overview.md). How the
frontend calls the backend (real shapes, unwrapping, SSE):
[../docs/guides/frontend-integration.md](../docs/guides/frontend-integration.md). Two structural
rules the layout alone does not convey: `views/board/` holds Board.vue's extracted domain logic as
pure, unit-tested modules with no component dependencies, and `router/loginRedirect.ts` is the
single owner of the "session gone → login" location.

- **Keep types aligned with backend DTOs.** Fields are camelCase on both sides
  (`userId`, not `user_id`). When a backend DTO changes, update the matching
  `src/types/*.ts` **and** the owning `docs/api/*.md` in the same change.
- **Two response-unwrapping conventions — do not mix them up:**
  - `board.ts` / `simulation.ts` / `fuzzing.ts` / `rules.ts` return **already-unwrapped** `T` (a local
    `unpack` returns `response.data.data`).
  - `authApi` returns the **full** `Result<T>` (`response.data`) — read `res.data.token`,
    not `res.token`, and check `res.code`.
- **`verifyAsync(req)` / `simulateAsync(req)` return the authoritative accepted task
  DTO**, including the server-generated `id`, current status/progress, and frozen
  `modelSnapshot`; the client does not pass an id in or fabricate a local task row.
  Acceptance does not mean the run completed.
- Verification/fix live on `boardApi` (there is **no** `api/verify.ts`); trace types
  live in `types/verify.ts` (there is **no** `types/trace.ts`).
- Counterexample exploration lives in `api/fuzzing.ts` and `types/fuzzing.ts`. A fuzz
  finding is replay-only candidate evidence: never expose the formal Fix action for it,
  and never render `BUDGET_EXHAUSTED` as safe or satisfied.
- Bilingual: user-facing strings go through Vue I18n (`assets` i18n, zh-CN + en) — do
  not hardcode display text. Backend/LLM free-text `message`, `reason`, `warning`, and
  `errorMessage` fields are technical diagnostics unless their contract explicitly says
  they follow the requested language. Prefer stable reason/status codes; otherwise use
  `utils/userMessage.ts` for ordinary fallback copy and keep the raw text in Technical
  Details or logs. Chat is the one surface whose contract *does* say so: `sendStreamChat` sends the
  current UI locale, and the backend writes its status prose in that language. Keep sending it — the
  fallback is a Han-character scan of the user's own message, which answered "hi" in English on a
  Chinese interface. A backend sentence that merely restates something the client already renders
  (a badge, a status chip) should be deleted rather than translated: the client's copy follows the
  UI language for free.

### Frontend anti-slop checks

- Keep one explicit owner for each server snapshot, local draft, pending mutation, and dialog
  intent. Background refreshes and late responses must not overwrite active edits or reopen a
  surface the user closed.
- Treat loading, empty, partial, stale, cancelled, unknown-outcome, and failed states as
  distinct. Never replace missing authoritative data with fabricated success, placeholder
  domain objects, or a toast that contradicts the persisted state.
- Reuse typed API validators and established composables. Avoid component-local copies of
  contract parsing, translation provenance, modal focus logic, or responsive breakpoints.
- Verify user workflows at narrow/short and desktop viewports, light and dark themes, keyboard
  and pointer input, low canvas zoom, slow responses, repeated actions, and cross-tab refresh
  whenever the touched surface can encounter them.
- Tests must assert accessible roles, user-visible outcomes, and authoritative reconciliation;
  do not couple them to obsolete raw tokens, CSS accidents, arbitrary sleeps, or mocks that
  bypass the failure being fixed.

## Gotchas (the "why")

- **The board's URL owns "which run is open", and nothing else.** `run=<kind>:<id>` plus
  `trace`/`finding` are the only deep-linked state; panel layout, widths, `activeSection`,
  and canvas pan/zoom are persisted server-side per user via `BoardLayoutDto`, so putting
  them in the URL would create a second authority. History openers and URL restoration use the
  same loaders; verification completion adopts its received result and saved address together.
  Never add a separate URL writer beside a result adoption. Opening is `push`,
  correcting/clearing is `replace`. Rules and the full inventory:
  [../docs/guides/frontend-ui-conventions.md](../docs/guides/frontend-ui-conventions.md).
- **A disabled submit must say why, inline.** Derive the disabled state and the message from
  one `*BlockedReason` computed and link it with `aria-describedby`; never also toast what the
  form already shows. Result-surface closers come in pairs: `close*` for internal transitions
  (which must not touch the URL) and `dismiss*` for a user-facing close (which clears its owned deep
  link). Getting this backwards makes a replay strip its own link, or leaves `run=` behind so
  the sync reopens the surface the user just closed.
- **Validate response cross-field invariants in both directions.** A one-way check leaves the
  mirror case free to fabricate: a `VERIFIED` strategy attempt with no suggestion passed because
  `fixable` is derived from suggestions alone, and a scenario could apply a whole scene while
  reporting zero inspected candidates because only the standalone path tied `validatedCount` to its
  kept items. When a count or status claims evidence, assert the evidence exists — and prefer a
  bound over an equality where a legitimate case differs (a synthesized adjustment adds an applied
  item with no validated candidate behind it).
- **Undo reverses a persisted board edit — nothing else.** It is not cancel, stop, delete-result,
  dialog close, or browser Back; each of those has its own control. The server journal is the
  authority: `useBoardUndo` keeps no snapshot stack, applies the authoritative collections the
  response carries, and reads availability from `/board/edits/availability`. Never intercept
  `Ctrl+Z` in an input, textarea, `contenteditable`, or during an IME composition
  (`boardUndoShortcut.ts` owns that scoping). Reversible: device create/update/rename/delete, direct
  Environment Pool edits, rule/spec create/update/delete, rule reorder, and automatic-fix apply. Device
  deletion is one compound entry containing its cascades and Environment Pool state; automatic fix
  is one ordered rule-set entry. Confirmed scene replace/clear, template deletion, and default-template
  reset are history boundaries because they can remove or replace manifest semantics used by snapshots.
  Show the server-reported history count before confirmation and re-read availability afterward.
  Clearing an unusable journal is separately confirmed and
  carries the exact `/board/edits/clear-preview` token; stale confirmation must preserve history.
  A non-`409` undo/redo failure is an unconfirmed mutation outcome, not proof that the board stayed
  unchanged: reconcile the complete snapshot through the mutation queue before reporting it. A
  `409` wrote nothing but still triggers reconciliation because the local board may be stale.
- **Shrink `Board.vue` by extracting rules, not by adding layers.** Worthwhile moves take their
  inputs as arguments (including `t`/`locale`) and land in `views/board/` or `utils/` with tests:
  boundary parsing, wording, decision tables, freshness predicates. Do **not** extract code needing
  many injected reactive getters — that trades size for indirection, the pass-through layer the root
  CLAUDE.md forbids. Two things that look extractable but are not: the three recommendation sections
  diverge in most of their markup (a shared component would need more slots than it saves), and the
  fuzzing refs are interleaved with simulation and task-inbox state rather than contiguous.
- **Every ordinary targeted device/Environment Pool/rule/spec mutation commits through
  `board/semanticCommit.ts`.** It applies the
  authoritative collections and then everything derived from them — canvas edges, dangling
  inspector focus, undo availability, verdict staleness — in a fixed order. Never hand-assemble
  those follow-ups at a call site: that is what let reorder skip undo availability and undo skip
  the canvas edges, each hidden by a later refresh that happened to repair the state. An undo is
  itself a semantic mutation, so it uses this path and must match `isBoardMutationRequest`. Pass
  `semanticChanged: false` only for a confirmed unchanged model (including a requirement rename); it still reconciles state and
  availability without making a current verdict stale. Authoritative partial/full refreshes are not
  mutations, but they must reuse `reconcileBoardFocus` so removed items cannot remain selected.
- **A path that changes the undo journal must re-read it.** Scene replace/clear calls
  `notifyUndoJournalCleared()`; reversible mutations carry availability or reload it, and a wholesale
  semantic reload (`refreshBoardSnapshot`) re-reads availability explicitly at the end. A board
  invalidation does
  **not** cover the publishing tab — `BroadcastChannel` never delivers to the context that posted,
  and `publishBoardInvalidation` only calls `postMessage` — so the origin tab depends on that
  explicit re-read, not on its own broadcast. The assistant relies on it, so an assistant-created
  device or rule is as undoable as a user-created one (`views/board/assistantRefresh.ts` owns the target
  table; verified against the real model in `e2e/live-ai-no-mock.spec.ts`). The assistant cannot
  delete in one turn — destructive tool actions need a confirmation token, so do not write features
  assuming otherwise. Availability reads run outside the mutation queue: every mutation response
  carrying availability must invalidate older reads, and only the latest concurrent read may land.
- **Every modal is composed from `styles/dialog.css`; nothing builds a dialog shell locally.** One overlay,
  one card, three sizes, four tones. Tone goes on the card and the header's icon tile reads it — do not also
  paint the confirm button in the tone (`--danger` excepted), or the primary action changes colour between
  surfaces. `__body` is the only scrolling part, the primary action is last in `__footer`, the card is
  **opaque** (`--surface-elevated`, never the 30%-alpha `--iot-color-card-bg`), and a dialog stays centred at
  every width — a narrow-viewport bottom sheet was reverted because Element Plus MessageBox cannot dock, so
  it gave one class of surface two positions. Address dialog controls by `data-testid`, never by an
  appearance class. `styles/__tests__/dialogSurfaceConsistency.spec.ts` enforces all of this; the reasoning
  and the measured before-state are in
  [../docs/guides/frontend-ui-conventions.md](../docs/guides/frontend-ui-conventions.md) §4.
- **All user feedback goes through `utils/feedback.ts`.** Call sites state the intent
  (`notifySuccess`/`notifyInfo`/`notifyBlocked`/`notifyError`, `confirmDestructive`,
  `acknowledge`), never `ElMessage`/`ElMessageBox` directly — that is what kept 421 toast
  call sites and three different confirmation styles from agreeing. A success whose result is
  already visible on screen gets **no** toast. Field validation is inline, not a toast. A
  failed load that leaves state unknown is a persistent banner, not a toast.
- **One owner for "the session is gone".** The axios 401 interceptor, the SSE transport,
  and `App.vue`'s auth watcher all route through `router/loginRedirect.ts`. Never build a
  second `{ path: '/', query: { mode: 'login', redirect } }` literal, and never re-read
  `localStorage` to decide whether the user is signed in — the auth store is authoritative
  (it initializes from storage at module load, before the first guard runs). The route guard
  calls `revalidateSession()`, which drops a session whose JWT expired while the tab stayed
  open; `isLoggedIn` alone is only decided at load/login and would keep reading as true.
- **Complementary media queries must split at one value.** Write
  `max-width: 1023.98px` / `min-width: 1024px`, never `1023px` / `1024px`: a fractional
  viewport width (routine on scaled displays) matches neither rule, so an element gets
  neither the narrow nor the wide layout. The same applies to `max-height: 599.98px` and to
  any hand-written breakpoint that must not overlap a Tailwind prefix — `sm:` starts at
  640px, so its compact counterpart ends at 639.98px.
- **`role="dialog"` implies a focus trap.** The board's floating tool panels
  (verification, simulation, fuzzing, run history) are
  non-modal on purpose: the canvas stays live and `useModalAccessibility` is given
  `trapFocus: false`. Those are `role="region"`. Anything that claims
  `role="dialog"` + `aria-modal="true"` must keep the trap — and therefore also gets the
  background scroll lock, which `useModalAccessibility` applies for exactly that case. A trapping
  modal also gets a document-level Escape fallback: the element-bound handler only sees the key once
  focus is inside the dialog, and focus arrives a tick later, so a deep link that opens the surface on
  load had its first Escape silently dropped. Non-modal panels are excluded — one keypress must not
  close several of them. Element-bound handlers must also ignore an already-default-prevented event,
  so an Escape consumed by a nested modal cannot bubble on and close its ancestor.
- **"Modal to the user" is not the same as "takes the scroll lock".** `openModalDepth`
  (`useBodyScrollLock`) is what tells window-level accelerators such as the board's Ctrl+Z that a
  surface is covering the board. Element Plus `MessageBox` confirmations pass `lockScroll: false` on
  purpose — the board shell is a fixed `100vh` surface that Element Plus's scrollbar compensation
  would shift — so they register depth through `registerModalSurface()` in `utils/feedback.ts`
  instead. Wiring depth to the scroll lock alone left every `confirmDestructive` window unguarded,
  and Ctrl+Z reversed the previous edit behind the prompt.
- **A `clamp()` whose middle term can never win is a fixed size wearing responsive syntax.** `cqmin`
  is a percentage of the *container*, so a sub-floor floor on a canvas node renders below the 11px
  minimum at every viewport. Compute what the preferred term evaluates to at the container's real
  size, and never exempt a pattern from `typographyFloor.spec.ts` on a claim you have not measured.
  Both, with the numbers: [../docs/guides/frontend-ui-conventions.md](../docs/guides/frontend-ui-conventions.md) §7.
- **A semantic role has an ink half and a paper half.** Fill under light labels with
  `--<role>-fill`; bare roles are text inks that lighten in dark mode. Disabled controls keep opaque
  labels. Use `hover:board-control-hover` with `hover:board-text-strong` for muted neutral controls:
  unlayered board rules outrank Tailwind utilities, and muted light-theme ink needs the stronger role
  on hover. Color ownership, neutral utility usage and contrast requirements:
  [../docs/guides/frontend-ui-conventions.md](../docs/guides/frontend-ui-conventions.md) §6.
- **A `position: fixed` overlay cannot read a variable scoped to the board.** The `--board-*` width
  and gap variables live on `.iot-board`, and the timeline hosts are siblings of it, so inside them
  `calc()` becomes invalid and the box shrink-wraps flush against x=0. Inject the variables onto the
  fixed element (`boardShellStyle` does this) rather than restoring a `var(…, 1rem)` fallback, which
  only hides it. Measurements:
  [../docs/guides/frontend-ui-conventions.md](../docs/guides/frontend-ui-conventions.md) §7.
- **Stacking order is a named scale, not a literal.** Add a layer to the `--z-*` block in
  `styles/base.css` and reference it (`z-[var(--z-modal)]` in Tailwind,
  `var(--z-modal)` in CSS). Values inside a component's own stacking context stay local
  and small. A raw four-digit `z-index` is a bug.
- **Side panels are optionally controlled.** `ControlCenter` / `SystemInspector` accept
  `activeSection` but must not declare a default for it: a default makes the prop look
  permanently "controlling", which silently discards internal selection changes when no
  parent is managing it. Their optional Boolean `collapsed` prop needs an explicit
  `undefined` default; Vue otherwise casts absence to `false` and blocks local collapse.

- **Base URL comes from one place, relative by default.** Both `http.ts` (axios,
  appends `/api`) and `chat.ts` (SSE) read `import.meta.env.VITE_API_BASE_URL`. Empty
  (default) → relative `/api` via the Vite/Nginx proxy; set an absolute URL only for
  cross-origin. Don't hardcode an absolute `localhost` URL (it bypasses the dev proxy
  and, in prod, points at the user's own machine). See
  [../docs/guides/frontend-integration.md](../docs/guides/frontend-integration.md).
- **Chat streaming bypasses axios**: `sendStreamChat` uses native `fetch` +
  `response.body.getReader()`, so it sets the `Authorization` header manually and the
  axios interceptors do not apply. Protocol:
  [../docs/api/chat-sse.md](../docs/api/chat-sse.md).
- **Rule references are node-id authoritative.** `RuleBuilder` stores new rule
  source/target device references as canonical `DeviceNode.id` values; labels are only
  shown to users. Never synthesize dummy trigger conditions in `board.ts`; validate and
  surface an error instead.
- **Verification warnings are user-visible.** `disabledRuleCount`,
  `skippedSpecCount`, and `[rule-disabled]` / `[spec-skipped]` entries in `checkLogs`
  must be shown even when `outcome === 'SATISFIED'`. There is no boolean `safe` field — it was
  removed because it collapsed "all specs passed" and "the model was faithful" into one bit, and
  those are the two questions a warning exists to separate.
- **Own a streaming row by identity, never by position.** The assistant's in-flight row is found by
  `turnId`: "load older messages" prepends a page and shifts every array index, which sent chunks
  and terminal status into an archived message. Browsing history during a stream is legitimate — do
  not "fix" this class of bug by disabling the operation.
- **Staleness belongs to the run, not to the surface showing it.** `lastSimulationResult` remains
  the playback manifest when the terminal is hidden or closed, so flag and clear against that run.
  Closing a surface is not a fresh result and must not clear a stale flag; only a new run is.
  That ref is also the manifest the replay bar describes — its attack/privacy chips, step counts and
  `modelSnapshot` — and `savedSimulationStates` derives directly from that same result.
  The adopter updates the result and staleness; callers do not maintain a separate state copy.
  A deferred background run may update the result while no replay is visible.
  A run completing behind an open replay is **deferred, not adopted**
  (with `notifyAutomaticPlaybackDeferred`), because adopting
  it replaced the visible trajectory and header while the canvas still belonged to the selected
  replay's frozen scene. Reachable in ordinary use: playback admission does not consider `isSimulating`, so
  replaying history while an async run finishes is normal.
- **Logout belongs to its initiating session.** Capture its token and account-page ownership
  before settlement; check both after every await and before local logout/navigation.
  The logout API must receive that pinned token, even if the current session changes.
- **A replay on screen outranks every arriving run.** The rule above generalises to all three kinds,
  because playback admission considers none of `isSimulating`/`isVerifying`/`isFuzzing`: an arriving
  run must not present a surface while `isModelPlaybackActive`. Verification is the sharpest case —
  opening a counterexample calls `closeResultDialog`, so `verificationResult` is null for the whole
  replay, and re-assigning it raised an `aria-modal` dialog over the trace being watched. Defer
  instead, and in every kind say where the run went (run history for verification and simulation, the
  task notification for exploration) at the severity the outcome deserves — a deferral must not read
  as a quieter result, and a budget-exhausted or violated run stays flagged as one.
  An arriving failure still emits its error notice, but opens an error surface only while playback
  is absent. Dismissing a result clears only its own URL target and preserves another run's replay.
- **A displayed verdict only describes the model that was verified.** Any semantic board
  change (applying a fix, editing rules/specs/devices from the inspector or chat) makes an
  open verification result stale: `Board.vue` flags it from the single semantic-scene-change
  hook in the mutation queue, then withdraws the per-counterexample Fix action and shows the
  re-run banner. Counterexample replay remains available because it renders the run's frozen
  scene rather than the current canvas. Never let a stale verdict keep offering actions that
  imply it describes the current canvas, and always clear the flag when a fresh result is
  presented.
- **Run history has two user layers.** Task Status contains only active or no-result
  failed/cancelled jobs. History Results contains one item per completed verification
  or saved simulation; verification counterexamples are nested summary evidence, not
  peer runs. Load full run/trace states only when opened, and keep malformed rows as
  unavailable placeholders rather than failing the whole list.
- **Exploration is background-only.** Closing its panel must not cancel the accepted
  task; keep it visible in the global task indicator/inbox and move completed work into
  History Results with nested finding summaries. Being background also means never seizing the
  screen: `FuzzingResultDialog` is `aria-modal`, so route a completion arriving mid-replay to the
  notification instead (see the replay-outranks-arrivals rule above), as `handleFuzzing` already
  does when its panel is closed.
- **A stopped chat transport is not a cancelled tool operation.** Wait for the session
  activity endpoint to become idle before switching/deleting the session or allowing a
  new assistant mutation, then reconcile board and run-history state. Match the reloaded
  terminal assistant row by `turnId`; never let an older completed turn replace the current
  local request.
- **Chat history is cursor-paged.** Preserve `nextBeforeId`/`hasMore`, prepend older
  pages without replacing recent messages, and remove optimistic turns only when the
  SSE request was rejected before transport acceptance.

## Reference (link, don't duplicate)

- Backend API contracts: [../docs/api/rest-endpoints.md](../docs/api/rest-endpoints.md)
  and the domain docs under [../docs/api/](../docs/api/overview.md)
- Exploration contract and role: [../docs/api/fuzzing.md](../docs/api/fuzzing.md) and
  [../docs/architecture/fuzzing-flow.md](../docs/architecture/fuzzing-flow.md)
- Config / env vars: [../docs/getting-started/configuration.md](../docs/getting-started/configuration.md)
