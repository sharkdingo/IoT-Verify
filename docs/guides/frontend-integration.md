# Frontend Integration Guide

How the Vue 3 frontend calls the backend: the HTTP client, the API modules and their
real shapes, SSE streaming, and where the TypeScript types live.

Verified against code on 2026-10-18 (workspace reload, failure ownership, account settings, and run lifecycle). Source: `frontend/src/api/`,
`frontend/src/types/`, `frontend/src/components/ChatView.vue`,
`frontend/src/views/Board.vue`, `frontend/src/App.vue`, and `frontend/src/router/index.ts`.

---

## Directory layout (actual)

```
frontend/src/
├── api/
│   ├── http.ts          # Axios instance (interceptors, unpack lives in board.ts)
│   ├── auth.ts          # authApi — auth calls
│   ├── board.ts         # default-export object: board CRUD + verification + fix
│   ├── chat.ts          # named exports: sessions + SSE streaming
│   ├── rules.ts         # rule recommendation only (persistence lives in board.ts)
│   ├── simulation.ts    # default-export object: simulation calls
│   └── fuzzing.ts       # default-export object: bounded exploration tasks/runs/findings
└── types/          # one module per domain — ls the directory rather than trusting a list here
```

> There is **no** `api/verify.ts` and **no** `types/trace.ts`. Verification calls live
> in `board.ts`; trace types (`Trace`, `TraceState`, `TraceDevice`, …) live in
> `types/verify.ts`.

---

## HTTP client — `api/http.ts`

Axios instance:

- `baseURL: (import.meta.env.VITE_API_BASE_URL || '') + '/api'`
- `timeout: 100000` (100 s)
- Request interceptor: attaches `Authorization: Bearer <token>` from the auth store.
- Response interceptor: records the token attached to each request. On HTTP `401`, it
  clears auth state only when that token is still the current token, then redirects to
  the landing page's integrated login panel: `/?mode=login`. This prevents a delayed
  request from the previous account from signing out a newly authenticated account. If
  the user was on a protected route, the redirect also carries the current full path.
  Standalone `/login` and `/register` routes were removed during the landing-page
  consolidation; do not reintroduce compatibility routes during the current development
  phase.

Both HTTP and SSE use `VITE_API_BASE_URL`; deployment values belong in the
[configuration reference](../getting-started/configuration.md#frontend-vite).

The endpoint columns below are **client-relative** paths passed to this axios instance
(`/auth/login`, `/board/nodes`, and so on). The actual HTTP paths on the backend include
the `/api` prefix, for example `/api/auth/login`; the authoritative backend index is
[../api/rest-endpoints.md](../api/rest-endpoints.md).

Current routes are deliberately small: `/` is the public landing page with integrated
login/register modes, `/board` is the authenticated workspace, `/account` is the
authenticated account page, `/404` is public, and
all unknown routes redirect to `/404` under hash history. Direct non-hash URLs are
normalized to hash URLs during app startup.

### Workspace reload

The auth store initializes from browser storage before the first route guard. The guard
checks locally visible JWT expiry; backend authentication remains authoritative for each
request. A board mount starts the semantic snapshot, task inbox, and saved layout reads
concurrently. Accepting a snapshot explicitly refreshes the model fingerprints and undo
availability once; semantic-change watchers do not duplicate an already pending fingerprint
read for that revision.

Layout restoration waits for the semantic snapshot so a narrow viewport can fit its devices,
but does not wait for the task inbox. Task recovery can therefore finish independently of
restoring the user's viewport and panels. Initial hydration and failed-layout recovery follow
the [board layout contract](../api/board.md). History result lists and full run details remain
lazy; an incoming run deep link is restored after semantic board data is ready.

Opening or refreshing a history layer reads current model fingerprints once. Result loading
uses the fingerprint read already owned by the fuzzing-history loader; task loading starts its
own read. A later explicit refresh still requests fresh fingerprints.
Opening a saved exploration result also keeps its awaited fingerprint read; displaying that
result does not start another one. Newly completed exploration tasks still refresh before
presenting their result.

Layout saves pin the initiating session token and belong to the board component's account.
Pending writes and late completions lose authority when that ownership changes; ordinary
navigation within the same session can still flush pending layout changes.

---

## Response unwrapping — two conventions

The backend's application JSON responses use the `Result<T>` envelope (authoritative
definition and protocol exceptions: [../api/overview.md](../api/overview.md)). What this guide owns is that the
frontend does **not** unwrap it uniformly:

| Module | Returns | Caller must read |
| :--- | :--- | :--- |
| `board.ts`, `simulation.ts`, `fuzzing.ts`, `rules.ts` (rule recommendation only) | already-unwrapped `T` (via a local `unpack` that returns `response.data.data`) | the value directly; targeted rule mutations map backend `RuleDto` values back to `RuleForm` and return the authoritative current collection |
| `auth.ts` | the full `Result<T>` (returns `response.data`) | `.data` for payload endpoints, `.code` for status; void responses may omit `data` |

So `authApi.login(...)` resolves to `Result<LoginResponse>` — after confirming
`result.data` exists, read `result.data.token`, **not** `result.token`. This asymmetry
is real; mirror it in components.

---

## Auth — `api/auth.ts`

Exported as an object `authApi` (not named functions):

| Method | HTTP | Endpoint | Returns |
| :--- | :--- | :--- | :--- |
| `authApi.login(data)` | POST | `/auth/login` | `Result<LoginResponse>` |
| `authApi.register(data)` | POST | `/auth/register` | `Result<RegisterResponse>` |
| `authApi.logout(authToken)` | POST | `/auth/logout` | `Result<void>` |
| `authApi.getAccount(authToken)` | GET | `/auth/account` | `Result<AccountProfile>` |
| `authApi.updateUsername(data, authToken)` | PATCH | `/auth/account/username` | `Result<AccountProfile>` |
| `authApi.changePassword(data, authToken)` | POST | `/auth/account/password` | `Result<void>` |
| `authApi.deleteAccount(data, authToken)` | DELETE | `/auth/account` | `Result<void>` |

Request and response fields belong in the [auth contract](../api/auth.md).
Login and registration rejection retain declared causes and fields beside the form.
Once authentication succeeds, a destination-page loading failure preserves the session
and offers Retry without repeating the authentication request. Retry reopens the saved
destination in a fresh browser document, retaining its query/hash and the session;
a failed native module import cannot reliably be retried within the same document.
When an initial lazy route fails before any page has mounted, the existing application
error boundary offers page reload instead of leaving a blank screen. A later navigation
failure remains owned by the mounted page. Module addresses stay in collapsed technical
details rather than replacing the user-facing recovery explanation.
Authentication responses are adopted only while their initiating session and page still
own the request; a later sign-in in another tab takes precedence over a late response.
On successful account deletion, clear local auth state and return to the landing page.
An explicit `4xx` deletion response is a definite rejection and leaves the form available
for correction, with its diagnostics inside the open deletion form. A missing response or `5xx` has an unknown commit outcome: clear only the
matching local session, return to login, and ask the user to sign in again to determine
whether the account still exists instead of retrying the destructive request.
The destructive dialog enables confirmation only after the user actually types, pastes,
or composes the identifier in that opening. A browser/password-manager autofill that merely
populates the matching value is not treated as deliberate confirmation.
The account page pins the initiating token through chat settlement and the logout or
deletion request. Each continuation checks the same page, user, and token; only the
matching local session is cleared and routed to login.
It stops active AI-chat responses before revoking that token; background
verification/simulation/exploration tasks are not cancelled. `deleteAccount` is the destructive path: it cancels active tasks,
serializes in-flight writes with account deletion, and removes user-owned data.

Authentication changes propagate between tabs through one JSON storage event
containing token and user together. Other tabs apply that record atomically and validate the
token before entering the logged-in state; they do not react independently to the separate
token and user keys, which could otherwise expose a half-login or half-logout snapshot.
Queued notifications apply only while their payload still matches the current shared
sync record. A delayed clear notification rechecks the current stored session, so neither
can undo a newer login or profile update performed in the receiving tab.
An expiry precheck clears only the session that still owns the shared token. If
another tab has already changed it, the store rechecks the current stored session
before the route guard decides whether to allow entry.
Private route components are keyed by authenticated user id, so switching accounts unmounts
the previous Board request/timer/subscription scope before loading the new account. The
assistant synchronously aborts and clears its selected conversation on the same boundary.
Cross-tab logout hides private content immediately and routes to the integrated login panel;
refreshing a token for the same user does not remount the workspace.

The account page is a private lazy route with a single workspace entry on desktop and
in the narrow navigation menu. `returnTo` carries the complete Board artifact address;
`router/accountReturn.ts` accepts only internal `/board` targets. The account Back action
restores that address, while browser Back/Forward use ordinary route history. Sign-out
and password-change login destinations retain the same Board address rather than
returning to a stale form. This parameter contains no credentials or form values.

Leaving the Board for the account page settles local recommendations and fix searches,
waits for queued writes, and makes a bounded layout flush. Accepted background
verification, simulation, and exploration tasks remain server-owned. A local result
without a confirmed save requires explicit agreement before the Board unmounts;
it cannot be silently relegated to history. The assistant conversation remains mounted
but hidden for the same account; navigation alone does not stop its accepted work or
discard its conversation. Assistant Board changes notify other tabs through invalidation,
and this tab loads the authoritative workspace state when the Board mounts again.

Account reads and mutations explicitly pin the initiating token. A late response must
still belong to that user, token, and page lifetime before updating visible state.
Guarded writes also check the token currently visible in shared browser storage, so
a session switch that precedes delivery of its storage event cannot be overwritten
by a late response. This is an observable ownership check, not an atomic browser lock.
The auth store owns the current username; a successful profile refresh or rename uses
its guarded user-update operation and broadcasts the same token with the authoritative
profile identity. It does not simulate a new login or publish a Board invalidation.

Username edits keep their original server baseline until submitted or explicitly
reviewed again after a conflict. Another tab's rename may update the displayed current
name but cannot silently rewrite an active draft's precondition. An unconfirmed rename
is followed by a profile read and a persistent review notice, not an automatic retry.

The account page starts in read-only mode. Only one credential editor is open at a time;
cancelling or switching editors clears secrets. Starting a new username edit refreshes a
profile whose name differs from the current auth identity before using its baseline.
Continuing freezes the requested name, expected server name, and initiating session for
the password confirmation. Only that confirmation submits the existing username command;
there is no separate password-verification request or reusable reauthentication token.
Cancelling the confirmation keeps the name draft but clears its password. Incorrect-password
rejections stay inside the confirmation. Name conflicts return to the visible editor for
review; they do not silently replace its precondition or resubmit the command.

The Board-to-account boundary owns recommendation/fix settlement and layout flushing;
password submission does not run a second Board preflight. Before password change,
sign-out, or deletion, the account page rechecks and settles active AI-chat work,
including work started in another tab. Failed reconciliation blocks the request with a
persistent explanation; declining an uncertain-outcome warning is ordinary cancellation.
A confirmed password
change clears only the initiating local session and returns to login with the Board's
full return address. A missing response or `5xx` uses the same guarded sign-out but
shows an unconfirmed-outcome notice. A definite field rejection retains the form and
login. While the password POST is pending, a private token-scoped intent lets the
shared HTTP interceptor label an earlier background `401` as an unconfirmed password
change. It still clears the session immediately; the late POST cannot reclaim it.
While an account mutation is pending, buttons and the route-leave guard block ordinary
Back/Forward navigation until the request and any reconciliation finish. Session loss or
replacement can still leave immediately; late responses cannot reclaim that session. Password values live
only in the form and request body; they are cleared on page unmount or session loss and
never stored in the auth store, browser storage, or URL.

Example:

```ts
import { authApi } from '@/api/auth';

const res = await authApi.login({ identifier: 'testuser', password: '123456' });
if (res.code === 200 && res.data) {
  const { userId, token } = res.data; // note: res.data, not res
}
```

---

## Board + verification + fix — `api/board.ts`

`board.ts` default-exports one object that covers board CRUD **and** verification/fix.
Its methods return already-unwrapped values. A failed initial semantic snapshot read keeps
editing unavailable and retains the declared cause and fields beside Retry until an
authoritative read succeeds. It does not also announce the same failure in a message.
Non-exhaustive:

- Board CRUD: `getNodes`/`addNodes`/`updateNodeLayout`/`updateNodeRuntime`/
  `renameNode`/`deleteNode`,
  `getSpecs`/`addSpec`/`removeSpec`, `getRules`/`addRule`/`removeRule`, and
  `getLayout`/`saveLayout`. Visible canvas connections are derived from `rules`; there
  is no persisted edges API.
  `BoardLayoutDto` owns both canvas pan/zoom and panel UI state
  (`panels.control` / `panels.inspector`: collapsed, width, active section); there is no
  separate active-tabs API.
- Templates: `getDeviceTemplates`, `getDeviceTemplateSchema`, `addDeviceTemplate`,
  `deleteDeviceTemplate`, `previewDefaultTemplateReset`, `resetDefaultTemplates`. Use
  `DeviceTemplateDto.defaultTemplate` to group default vs custom types. Reset is a
  two-step command: render the preview's itemized type/device/environment effects and
  blockers, then submit its `impactToken`. Reconcile both `currentTemplates` and
  `environmentVariables` from the committed response; do not infer success from a count.
  After a confirmed template import, a failed catalog read marks template state unavailable
  and reports the committed import beside the file control. The page's Retry control
  reloads the catalog without repeating the write. The notice clears only when the
  authoritative catalog contains the confirmed template id. File reads, writes and
  late catalog responses remain fenced to the selecting account.
- Control Center template UI is split into **Create Template** and **Template Repository**.
  Clicking a template card opens its detail preview; dragging a template card to the
  canvas opens a device-instance naming dialog and only saves after confirmation. While
  a template drag is active, the empty-canvas guidance and its button area become
  pointer-transparent so every visible canvas position is a valid drop target. Cancelling
  the naming dialog leaves the Board empty and restores the guidance actions.
  During trace playback or an atomic scene replacement, Control Center keeps read-only
  inspection actions (template preview, search, export, and schema download) available but
  disables device/template/rule/specification mutation entry points, including template
  drag, import, delete, and bundled-default reset. If that read-only state begins while a
  specification-condition editor or template confirmation is open, the local surface closes;
  an asynchronous impact preview that finishes later must not reopen it.
- Clicking a canvas device opens the device detail/runtime dialog. Its visible instance
  actions include rename and confirmed delete; rename must not depend on discovering a
  hidden mouse gesture. The runtime section keeps its title and single Save action visible
  while its fields scroll; the longer explanation stays in normal flow and must not cover
  correction feedback. Mouse right-click opens the same device context actions, while
  the Context Menu key or `Shift+F10` provides the keyboard equivalent. Runtime edits
  (`state`, `currentStateTrust`, `currentStatePrivacy`, device-local `variables`, and
  device-local `privacies`) use the same validation helpers as single-device creation and JSON/CSV
  import, then replace the complete runtime subresource through the targeted device
  commands defined in the [Board API](../api/board.md#canvas-storage). Moving/resizing
  uses the separate complete layout command. The Board serializes create, layout,
  runtime, rename, and confirmed-delete calls before applying their authoritative
  `currentNodes` snapshots; a late response therefore cannot roll back a later device
  action. Environment
  variables are not device runtime fields; edit their shared value/trust/privacy through
  the documented [Environment Pool compare-and-set contract](../api/board.md#boardenvironmentvariabledto).
  Keep the baseline from the latest authoritative read until the mutation settles, and
  refresh a stale row before retrying rather than silently overwriting another tab.
  Environment values are required and use their template's declared integer or enum domain.
  Device-local overrides use the same domain validation but may be omitted to use the
  template default. Rejections stay beside the relevant draft without replacing canonical state.
  An unconfirmed runtime write is reconciled before another submission: a successful
  read distinguishes a matching configuration from a difference; a failed read leaves
  the write outcome explicitly unknown and asks the user to reload before resubmitting.
- Device creation supports single, batch, and JSON/CSV import modes in the Devices tab.
  Single-device local values use the shared template-domain validation before submission;
  invalid values retain an inline field reason and block creation, while omitted values
  continue to use defaults. A definitive server rejection retains its declared fields in
  the form without a duplicate message. Once submission settles, focus the failure and
  bring its cause and creation button into view only if they still belong to the current draft.
  Editing or leaving that draft retires its feedback;
  a late successful save must not clear a newer draft. An unconfirmed creation refreshes
  the affected state before reporting an observed device or an explicitly unknown outcome.
  Batch/import are frontend conveniences over the targeted `addNodes` contract: parse,
  preview, generate unique labels, then append the accepted devices once. Device create,
  layout/runtime/rename/delete, rule/spec create/delete/reorder, Environment Pool edits,
  device-type import/delete/default reset, and confirmed scene replacement share one
  client-side mutation queue. Each operation applies its authoritative response or
  completes error reconciliation before the next request starts, so an older collection
  response cannot overwrite a newer local action. This is frontend response ordering,
  not a claim that separate ordinary REST calls form one transaction; only one batch
  replacement request is atomic as a group.
  Every successful axios mutation of Board semantics, including portable scene import
  through `/board/scene`, and every completed assistant Board
  refresh publishes a same-user `BroadcastChannel` invalidation. Receiving tabs enqueue one
  coalesced full semantic snapshot refresh through that same mutation queue; an invalidation
  received while hidden or while another refresh is running remains pending. Visibility and
  focus refreshes remain as the compatibility/recovery path when `BroadcastChannel` is not
  available. This reconciles device, template, Environment Pool, rule, and specification
  changes made in another tab without overwriting the current tab's pending mutation;
  initial mount, manual retry, and invalidation reads all use the same coordinator. A delayed
  initial response must complete before the pending invalidation fetch begins, so it cannot
  arrive after and overwrite a newer cross-tab snapshot.
  persisted canvas/panel layout remains a separate concern. Read-only POST operations,
  including rule/specification recommendations and rule duplicate/similarity validation,
  do not publish invalidations or make another tab reload an unchanged Board.
  The UI generates an opaque stable `DeviceNode.id` independently of the user-visible
  label. Renaming a device therefore never changes rule/spec identity, and labels are
  compared case-insensitively without applying NuSMV normalization rules to what the
  user sees. Single creation and rename keep the exact typed label and block a conflict;
  they do not commit an unconfirmed suffix. Batch/import deliberately allocate unique
  suffixes in the visible preview, then abort the whole append if the current Board has
  drifted and those previewed names are no longer available.
  Device import accepts either one JSON object or a JSON array with
  `template`/`templateName`/`type` plus `name`/`label`/`deviceName`, or CSV/tab-separated
  rows with two columns: template name, device name. A `template,name` header is optional.
  JSON rows may also include `state`, `currentStateTrust`, `currentStatePrivacy`, `variables[{name,value,trust}]`,
  and `privacies[{name,privacy}]`; these are validated against the selected template
  with the same runtime semantics used by single-device creation and backend board saves.
  Any other top-level or nested JSON field rejects that row and is named in the preview;
  it is never ignored as optional metadata. CSV continues to accept exactly two columns.
  `IsInside=true` local variables are saved on the created
  device instance. Environment variables declared by the selected template are split out
  of the imported row and sent as environment patches in the same atomic device-create
  request; they are not stored on the
  node. Variables affected only through `ImpactedVariables` are created by the environment
  pool self-heal path and are edited from the Environment Pool.
  Missing template names, unknown templates, missing device names, malformed JSON, malformed
  quoted CSV, extra CSV columns, invalid runtime state/trust/privacy/variable values, and
  duplicate runtime overrides are shown in the preview and disable creation. Duplicate
  device names are made unique in the preview (for example `ac` -> `ac_1`) and remain
  creatable. If two JSON rows provide different value, source, or sensitivity fields for
  the same scene-shared environment variable, the preview names both rows and disables
  the whole batch; identical or complementary fields merge. When batch creation includes
  environment-variable patches, the backend
  appends the devices and updates the environment pool in one transaction.
- Scene import/export lives in the Board header as a whole-board portability affordance,
  not as a backend endpoint. Export requests a browser download for an
  `iot-verify.board-scene` JSON file with
  exact `schema`, `version: 5`, referenced `templates`, `devices`,
  `environmentVariables`, `rules`, and
  `specs`; there is no timestamp inside the JSON because scene files are meant to be
  canonical and round-trip comparable. The toast reports that the download was requested,
  not that the browser or file system completed it; device-template JSON export uses the
  same wording. Export sorts templates/devices/environment and
  object keys into a stable order, but preserves authored arrays such as `rules[]`, rule
  `sources[]`, `specs[]`, and spec condition lists because order participates in rule
  precedence and preserves the user's authored semantics. Canonical template manifests
  omit optional object properties whose value is `null`, matching the backend DTO response
  shape; explicit `null` and omission therefore compare as the same round trip. Array order,
  empty strings, `false`, zero, and every non-null value remain significant. Public fix
  localization uses stable adjustment ids rather than array positions. Rule ids are omitted so
  an imported scene creates fresh backend rows, as do specifications; only stable device ids are
  preserved.
  Import rejects a missing/different schema or any unsupported version instead of
  guessing aliases and rewriting future data into version 5. It validates duplicate
  device ids, required identity/position/size, state required only for state-machine
  templates, state forbidden for stateless templates, required rule target command fields
  (`toId`/`toApi`), supported relation operators, specification template condition-side
  shape, and rule/spec device references in the browser, then uploads the validated portable
  file itself to `/board/scene` — the server owns the mapping onto the write contract, so the
  browser sends no internal rule/specification shapes.
  Before showing the destructive confirmation it calls `/board/replacement-preview` and
  displays the authoritative current server counts. The confirmed request carries that
  opaque `impactToken`; a `BOARD_REPLACEMENT_STALE` 409 means nothing was replaced, so
  refresh and require another confirmation rather than retrying automatically. The batch
  API has no null-means-preserve form: all four semantic collections and the exact
  template dependency array are required.
  Geometry validation is shared with the persisted canvas contract: finite coordinates
  are limited to `-1000000..1000000`, width to `80..2000`, and height to `60..2000`.
  This exact domain also applies to AI scene drafts, preserving export/import closure at
  the range boundaries.
  The canvas may hold `Working` as a stateless rendering placeholder, but version 5 export
  omits it together with current-state trust/privacy; import restores it only internally.
  Specification `templateLabel`, formula/device caches, and condition display metadata
  are excluded and rebuilt from `templateId` plus conditions. The environment pool is
  exact: every variable required by the referenced templates must appear once with
  explicit trust/privacy labels; missing, duplicate, or unknown entries reject import
  before confirmation.
  A scene is self-contained: it must carry exactly one matching snapshot dependency for
  every referenced template and no unreferenced snapshot. The backend enforces that rule
  even when the target account already has the template, atomically rejects same-name
  manifest mismatches, creates only missing referenced templates, validates the exact
  submitted environment pool, and saves nodes, environment, rules, and specs. A failed import does not trigger a client-side
  compensating write, because the server transaction leaves the prior scene intact.
  The response reports `createdTemplates` so the UI can state any template side effect
  explicitly. The Board header also exposes **Clear Scene**, which saves empty
  nodes/rules/specs and an empty environment pool through the same batch authority. Its
  destructive confirmation explicitly states that the device-template catalog, run
  history, and AI conversations are outside the scene and remain intact.
  Rejected local files and server validation remain in a diagnostic dialog until
  dismissed, with item coordinates, file field paths and the actual cause. Syntax
  errors retain parser-supplied coordinates; template-definition failures do not
  become syntax errors. Structured conflict reasons select the appropriate correction
  advice; see the [board import contract](../api/board.md) and
  [error conventions](../api/overview.md). A missing mutation response still requires
  authoritative reconciliation before describing whether the scene changed.
  A file read belongs to its selection, account and mounted Board. A newer selection
  retires an older read, including its late errors; the same owner guards replacement
  preview and confirmation before a write is dispatched.
- Recommendation: `recommendRelatedDevices`, `recommendSpecifications`, and
  `recommendScenario`. `recommendScenario` returns a canonical, importable but unverified
  `iot-verify.board-scene` draft for the Action Dock scene workflow; applying it should use the same scene-import
  path as file import so template checks, environment-pool validation, atomic batch
  saves, and user-visible template creation stay identical. Device recommendations return
  instance proposals with an effective `suggestedLabel`, optional advisory
  `intendedUse`/`suggestedPlacement`, and materialized local runtime values. Omitted
  trust/privacy fields remain template fallbacks rather than copied instance overrides. The two
  advisory fields explain recommendation context but are not persisted device fields or
  formal-model inputs. Omitted template defaults are listed in
  `adjustedItems` and must be shown before application; explicitly invalid runtime values
  reject the whole candidate. Apply them as device instances, not merely as template badges.
  Before creation, show any currently missing Environment Pool names required by the
  selected template; after creation, reconcile and display the server's authoritative
  itemized environment changes.
  Rule recommendation `name` is the exact display name persisted on apply. Specification
  recommendation `rationale` is explanation only; applying persists `templateId` and
  structured conditions. Neither candidate type exposes `priority`: relevance is return
  order, while rule precedence remains the Board's explicit execution order and every
  specification is checked. Treat a missing name/rationale as an incomplete response.
  The scene tool's LLM device ids are temporary aliases. The backend returns portable
  `device_1...` ids after rewriting every rule/spec/content dependency, reports rejected
  candidates in `filteredItems`, and reports deterministic defaults or inserted required
  environment values separately in `adjustedItems`; the panel shows both categories
  before the user confirms full scene replacement. The REST scene type is the exact
  portable shape, not a reuse of broader Board DTOs, so serialization cannot add null
  template ids/flags, condition display caches, or state-label fields that file import
  would reject. Optional rule titles stay optional and never cause a semantically valid
  AI rule to be filtered.
- Every recommendation attempt owns a fresh opaque request id. Stop makes five short backend
  cancellation attempts to cover registration races and aborts the POST transport only after
  one is accepted, then keeps the panel in a truthful stopping state until the status API
  reports `FINISHED`. An early `404` is treated as a possible registration race. Thirty
  consecutive unavailable status reads end recovery with a visible outcome-unknown warning
  rather than claiming cancellation or spinning forever. Sign-out and component teardown retain
  the request owner's identity and credential before retrying; same-user token renewal updates that
  credential, while a later account switch cannot make POST, status, or cancellation calls use
  another user's credential. A recommendation POST that receives no HTTP response retains its
  request id, controller, owner credential, and busy state while the same bounded cancellation/status
  recovery runs; it cannot allocate a second request id. A received HTTP response, including a
  client-side validation failure of that response, is terminal only for its own request. Sign-out
  accepts either acknowledged cancellation or owner-authenticated `FINISHED` status. Applying one
  standalone recommendation retains only that applied item as a disabled confirmation; all other
  candidates from the now-stale Board context are discarded.
- Device creation: manual creation, device-list JSON import, standalone AI device
  recommendation, and assistant `add_device` all materialize the same template-local
  starting state/variable values. The shared `materializeDeviceRuntimeConfig` helper
  overlays explicit values and label overrides while leaving omitted trust/privacy
  labels template-owned. In device-list
  JSON, omitted or `null` scalar fields select defaults; blank strings are validation
  errors. Standard scene files carry explicit persisted overrides; omitted labels continue
  to resolve from the imported template.
  Device display names are case-insensitively unique in every entry path. Manual creation
  blocks a conflict; batch/JSON import shows the exact suffix in its preview; an AI
  recommendation asks the user to confirm the exact available name before writing. If
  that name conflicts again while queued, creation aborts instead of adding another
  unconfirmed suffix.
- Recommendation application has separate processing and committed states. A rule,
  device, or specification candidate says "Applying, not confirmed yet" while its
  validation/similarity gate or persistence request is outstanding. It says "Added to
  board" only after the authoritative response, or after reconciliation proves the item
  exists. Application state is scoped to the recommendation response epoch, so a late
  completion cannot mark the same array position in a regenerated list. Once application
  changes the Board, the panel keeps only that committed candidate with its Added marker
  and removes candidate-accounting metadata from the obsolete response. A neutral note
  explains discarded candidates only when the previous result actually contained others.
  Closing the panel does not cancel reconciliation of an unknown write outcome;
  the authoritative Board collection still refreshes if the original authentication subject
  remains current.
- Scene import/clear acquires an interaction lock before showing its destructive
  confirmation and after earlier Board writes drain. Confirmation counts therefore
  describe a stable Board; live edits, new assistant requests, verification, and
  simulation stay blocked through response reconciliation. A streaming assistant must
  stop or finish first because aborting SSE cannot revoke a tool call already started.
- Verification: `verify(req)`, `verifyAsync(req): Promise<VerificationTask>`, `getTasks`,
  `getTask`, `cancelTask`, and trace list/detail/delete. Polling reads each full task snapshot.
- Counterexample exploration: `startAsync(req)`, task polling/cancellation, and run
  list/detail/delete. Replay lazily loads `getFinding(findingId)` and checks the requested
  parent run id against its closed replay envelope. Finding evidence, frozen playback
  scene, and model snapshot come from that same validated response. The accepted task is authoritative; the client never
  sends a Board/model payload or fabricates a task ID.
- Fix: `getFaultRules(traceId)`,
  `fixTrace(traceId, request?, { authToken, requestId?, signal? })`,
  `getFixRequestStatus(requestId, authToken)`, `cancelFixRequest(requestId, authToken)`,
  and signed apply. `FixResultDialog` uses a fresh id and pins the initiating credential on the
  POST, status reads, and cancellation calls for each search; without that credential it does
  not dispatch the search. An HTTP response, including a rejected response contract, is terminal for
  local request tracking. A POST failure without any HTTP response is instead an unknown
  admission outcome: the dialog retains the request id and owner credential, enters bounded
  cancellation/status recovery, and prevents another fix search from starting under a fresh
  id. Thirty consecutive unavailable status reads stop automatic polling and abort the POST
  transport, but retain the unresolved guard and make logout report `outcome-unknown` until
  cancellation is accepted or authoritative status reports `FINISHED`. Board keeps the dialog's
  request owner mounted while the surface is hidden, so closing cannot lose logout reconciliation
  or permit a new request id. It also keeps that owner bound to its trace, so opening another trace
  cannot mix old suggestions with the new trace id. Any current `FINISHED` status terminates a POST
  transport that never settled, and request-id fencing prevents its later `finally` block from
  clearing a newer search. Terminal evidence updates an uncertain failure notice without inventing
  a repair result; a late cancellation response cannot restore that obsolete warning. A trusted
  fix result replaces an earlier fault-loading error, including a valid empty fault-rule array.
  Status and cancellation transports use a 2.5-second timeout so retry and
  logout loops cannot inherit the ordinary 100-second API timeout. Route teardown still retries
  owner-authenticated cancellation before aborting the POST transport.

> **`verifyAsync` signature**: `verifyAsync(req): Promise<VerificationTask>` — it takes
> only the request and resolves to the authoritative accepted task. The client reads the
> server-generated `task.id`, persisted `status`/`progress`, and frozen `modelSnapshot`;
> it does not fabricate a local task row or pass an id in. Acceptance is not completion.
> If this promise rejects with validation (`400` DTO errors or `422` service/runtime
> errors) or service-unavailable errors, no pollable task id exists for the UI flow;
> show the submit error and do not start polling.

Rules sent through `addRule` or `checkDuplicateRule` must have
at least one concrete trigger source. The frontend stores new rule source/target device
references as canonical board node ids, and `board.ts` maps them to backend
`RuleDto.conditions` / `command`. `RuleSource.itemType` is required and maps directly to
backend `targetType`.
Definitive `400`/`422` rule/specification save rejections retain the submitted draft and
declared field diagnostics in the editor, including invalid values and references removed
by another entry point. Guidance identifies a duplicate only from its declared reason code.
Specification save feedback appears after the editable conditions and before the generated
description/formula preview, so correction takes priority over the preview.
Concurrent duplicate rule/specification rejections keep the submitted draft and correction
feedback in the editor. They reconcile the corresponding authoritative collection and undo
availability, since another entry point may have created the item. Read failure leaves that
collection unavailable rather than describing the rejected save as successful. Late partial
reads cannot publish into a different account or a disposed Board view.
Verification/simulation request builders send only run options. The backend captures the saved
Board and converts device references to model `varName` values. `utils/modelRequest.ts`
normalizes local references and builds run options; model comparison reads the authoritative
identities from `boardApi.getModelFingerprints()`.
See [model input authority](../architecture/data-authority-model.md#model-request-boundary).

The rule builder must keep source conditions and target commands separate. Source
`itemType="api"` is a signal event and should list only template APIs with
`Signal=true`, without relation/value fields. Target commands may list all template
APIs because they are actions invoked by the rule.
Selecting or filling a source edits a draft row only. Add it to the rule after an
explicit **Add source** action (or Enter from the value field); selection changes and
field blur must not silently commit a trigger while the same explicit action remains
visible.
Content sensitivity is a separate optional rule input and is shown only after the chosen
target API declares `AcceptsContent=true`. Changing the target device/action to one that
does not accept content clears both content fields; hidden stale values must never be
submitted. The selected source item contributes only its `public`/`private` label, not
payload bytes or a guarantee of delivery.

For value-based rule/spec conditions, dialogs should use canonical relations
`=`, `!=`, `>`, `>=`, `<`, `<=`, `in`, and `not in`. Enum-like mode/state/API/trust/privacy
conditions should expose only `=`, `!=`, `in`, and `not in`; numeric variables should expose
those operators plus ordering comparisons. Enum-like sets can serialize as comma-, semicolon-, or pipe-separated values. Multi-mode
`state` options are full tuples such as `home;idle`; therefore state sets must serialize
tuples with comma or pipe separators, for example `home;idle,sleep;idle`, so the
semicolon remains part of the tuple rather than a list delimiter.
REST and AI entry points also accept relation aliases such as `EQ`, `GTE`, and
`NOT_IN`, but storage and model requests are normalized back to the canonical values
above. Frontend controls should keep emitting canonical values so users see one stable
vocabulary.

`addRule` and `removeRule` return `CollectionMutationResult<RuleForm>` with the exact
affected rule plus the authoritative current collection. Callers replace local rule
state with `currentItems` so temporary ids are exchanged for stable server ids and
concurrent changes are not erased. Omitted rules are never interpreted as deletions.

The inspector presents the returned list as execution order. If multiple enabled rules
command one device mode in the same model step, the first matching rule wins for that
mode. Up/down controls send the complete current id order to `PUT /board/rules/order`;
the server rejects duplicate, partial, or stale lists and returns the authoritative
ordered rules. Search disables reordering because a filtered subset is not a complete
semantic order. This targeted command preserves rule ids and never uses `/board/batch`.
After a reorder, the UI states that model behavior may have changed and verification
must be run again.

Board layout and visual shell rules:

- Persist user layout through `BoardLayoutDto.panels` plus `canvasPan`/`canvasZoom`.
  Do not reintroduce a separate active-tabs endpoint.
- Treat the persisted layout as the wide-screen workspace. At narrow widths, collapse
  both side panels locally, fit the current nodes into the available canvas, keep a
  permanent 44px fit control reachable, and do not overwrite the saved pan/zoom or
  panel state. Restore that saved wide layout when the viewport widens again.
- Treat node positions as canvas world coordinates. Keep node geometry in pixels, but
  make surrounding UI chrome responsive with CSS grid/flex, `clamp()`, `dvh/dvw`, and
  board-level CSS variables.
- Canvas devices use three information-density tiers derived from on-screen node size:
  compact nodes show the icon only, medium/default nodes show icon + name + state, and
  expanded nodes show runtime chips. The default 176x128 node size should be large
  enough for the detailed tier at normal zoom, and the whole node is the detail-opening
  target; do not add a second detail button inside the node.
- Paint the grid on the viewport-sized canvas shell and offset it from `canvasPan` /
  `canvasZoom`; do not paint it on a finite transformed inner layer, otherwise users
  will see grid edges while panning an infinite canvas.
- Board surfaces, cards, forms, timelines, mini task indicators, and history panels use
  semantic CSS tokens from `base.css` / `board.css`. Prefer `board-surface-panel`,
  `board-card-surface`, `board-muted-surface`, and `board-segmented` over one-off
  `dark:` utility chains.
- Keep the entity model discoverable: `ControlCenter` owns the detailed create/edit
  forms, while `SystemInspector` owns the current-board lists. The inspector uses
  device/rule/specification tabs rather than one long stacked list, and each tab keeps
  a local add action that opens the matching control-center form. The visible entity
  section has a consistent collapsible header, a local search field, and filtered/total
  counts; rule/spec searches must match rule/spec semantics (conditions, actions,
  formulas, operators, values, and related devices), not only device names. Persist both
  active sections in `BoardLayoutDto.panels`.
- Timeline controls are bottom-centered between the side panels. When a trace or
  simulation animation is visible, viewport calculations reserve bottom space so
  fit-to-content and center-selection do not place nodes under the timeline. The
  timeline supports both explicit jump controls and direct track clicks; playback
  and manual jumps must update the same selected state so node, edge, and
  environment-variable highlights stay synchronized. Playback is a read-only view of
  saved model-trace data: node state must never fall back to the current Board value.
  Current Board devices absent from the saved trace remain only as dimmed spatial
  context and are identified as not represented. Live device details/editors are not
  opened from playback because they describe the current Board, not the saved state.
  A background result must remain in run history instead of forcing playback over an
  editor that the user is actively using. Keep playback controls and the selected-state
  delta in the first visible layer. Frozen-snapshot/model-scope explanations belong in
  a collapsed run-scope section, while incomplete-model, shortened-horizon, and current-
  Board drift warnings remain visible because they change how the trace may be
  interpreted. Simulation run details must report `states.length`, actual `steps`, and
  `requestedSteps` separately (`states = steps + 1` when parsing is complete); execution
  logs, per-state tables, and possibly truncated NuSMV diagnostic output are sections
  opened on demand, not the primary playback surface.
- Canvas rule edges are derived from `rules` whenever either nodes or rules change.
  Do not cache or persist edge rows. Keep edge labels hidden by default and reveal
  them on hover/focus through the SVG hit area so dense boards stay readable while
  keyboard users can still inspect each connection. During trace playback, edge
  highlighting must use backend `TraceStateDto.triggeredRules` stable rule snapshots.
  Do not re-evaluate conditions in the browser or depend on generated event-signal names.
  During playback, reserve animation for the delta that produced the selected state:
  changed devices receive a localized highlight, while a separate draggable change
  panel shows their user-facing before/after values and security-label changes. Do not
  place the full delta inside a device node: compact nodes can hide it or let it cover
  neighboring content. Backend-reported delivered rules emphasize their edges with
  command flow, while compromised delivery points remain static and red. Unchanged trace
  devices retain quiet context instead of pulsing continuously. Use the same semantic
  device comparison for the canvas and the change panel, including mode, compromise,
  trust, and privacy changes. Cross-fade to the selected model value immediately; do not
  keep an old icon/value visible until an animation timer expires. Keep command-flow
  duration shorter than one playback step and honor `prefers-reduced-motion`.
- After creating a device from the manager, drag/drop template dialog, batch import, or
  JSON/CSV import, focus the created device on the canvas and mark the Devices inspector
  row. After creating a rule, focus the created rule's derived edge(s), pan to the
  involved devices, and mark the Rules inspector row. This focus is an orientation aid
  only; it does not open the device detail dialog.
- Device detail semantics come only from the current template catalog response. Do not
  recover a missing manifest from `localStorage` or another browser cache: a stale
  template can contradict the model that verification will build. When the current
  template cannot be resolved, keep the instance identity visible, hide capability and
  formula sections, and ask the user to refresh before editing or interpreting them.
- The canvas map is integrated into the `SystemInspector` overview slot on the right
  side, above the device/rule/specification tabs. It is **always visible while the
  inspector is expanded** — it is not hidden when a result panel opens. The floating
  panels carry a `right` inset that clears the inspector and the action rail, so they
  cannot overlap it (measured: a panel spans x=660..948 against an inspector at
  1120..1440 at 1440x900), and its zoom field, zoom buttons and fit-to-content are the
  board's only pointer viewport controls — a result panel is precisely when a user zooms
  to inspect what it points at. At narrow widths the inspector collapses to a rail and
  the map is not rendered. Keep the map compact, theme-aware, and neutral; red remains
  reserved for warnings, violations, and destructive actions.
- The Environment Pool in `SystemInspector` has its own Environment tab alongside devices, rules, and specifications. It groups
  current board variables returned by `/board/environment`, using templates to explain
  which devices read or affect each variable, and displays the user-facing variable name,
  range, shared value, trust, and privacy. Backend-only NuSMV names such as `a_<name>`
  stay out of this UI surface. The component receives the
  persisted environment pool from `/board/environment` and saves each edit through the
  [name-keyed compare-and-set contract](../api/board.md#boardenvironmentvariabledto).
  The UI retains the baseline used by the in-flight edit, reconciles from the returned
  authoritative pool, and refreshes the affected row after the documented stale conflict.
  It says whether the selected field changed and never rebuilds a whole
  row from possibly stale props. Device creation/deletion triggers a refresh so the backend can insert
  defaulted required variables and prune unneeded ones. The tab count includes each shared name once;
  its searchable rows expose details on demand. Layout and interaction conventions live in
  [frontend UI conventions](./frontend-ui-conventions.md#13-workspace-shell-and-concise-copy). Clicking a source device is a navigation affordance only;
  source devices do not store the environment value. Simulation and verification
  timelines use the same user-facing names from `TraceStateDto.envVariables`; generated
  NuSMV aliases are not displayed or matched as a frontend contract. Runtime NuSMV
  globals such as the actual `compromisedPointCount` are exposed separately through
  `TraceStateDto.globalVariables` so they cannot collide with user environment names.
- The simulation/counterexample-exploration/verification/history/recommendation actions live in a right-side tool
  rail placed just outside the inspector, never overlapping the inspector itself.
- Model-trace playback is one exclusive review surface. While either the simulation
  timeline or counterexample timeline is open, the Action Dock cannot open run history,
  task inbox, run settings, or recommendations; handlers enforce the same guard as the
  disabled controls. Users close the current trace before choosing another saved run.
  Desktop widths show icon+text buttons grouped as run tools and AI suggestion tools;
  narrower viewports collapse the labels but keep `aria-label`, `role="toolbar"`, and
  grouped `role="group"` semantics. Desktop floating panels open to the left of this
  rail so close buttons and form controls are never covered by toolbar hit areas. Do
  not use unexplained red corner badges for active state; prefer the button's selected
  or disabled state and the global task/timeline indicators.
- Stable `data-testid` attributes exist for the board root, side panels, control tabs,
  canvas, canvas map, history panel, mini task indicator, simulation/verification
  panels, recommendation panels, and trace/simulation timelines. Real-browser tests
  should use these instead of localized text.
- `AppErrorBoundary` contains route-view and lazily mounted assistant render failures in
  separate subtrees. Its localized fallback offers a subtree retry and an explicit full-page
  reload; changing routes resets the boundary. Do not move both surfaces behind one boundary,
  because an assistant failure must not remove the Board and a route failure must not prevent
  recovery controls from rendering.
- Recommendation panels must use i18n keys for labels, counts, empty states, and action
  buttons. They are opened in real-browser tests under both English and Chinese locales.
  Scenario counts use one validated request projection for inline errors and submission;
  invalid count fields are linked to the visible reason and generation remains disabled
  until corrected. Invalid criteria never claim request ownership or call the AI endpoint.
  Request failures retain localized guidance, visible causes/field errors and expandable logs in
  the owning panel, separate from a successful empty candidate list. Unknown requests
  remain under the existing status-recovery owner; after confirmed termination without
  a result, the panel explains that no complete result was received. Scene/request
  generation guards prevent late failures from restoring a discarded panel context.
  Rule/spec/device recommendation calls pass the active locale as `language`; backend
  fallback/success messages and LLM natural-language fields should follow that language.
  When recommendation responses include `filteredCount > 0`, surface a neutral validation
  note plus the first few `filteredItems[]` reasons (`type`, 1-based `index`,
  localized `reason`, optional `label`) instead of making the smaller result count look
  like an unexplained failure. When `truncatedCount > 0`, also surface that raw AI
  candidates were not inspected because the requested result limit had already been
  reached. A coupled-scene recommendation belongs to the exact minimums, maximums, and
  requirement text submitted for that attempt. Editing any of those fields invalidates the
  displayed result immediately; export and full-scene replacement remain unavailable until a
  fresh response for the edited criteria arrives.
- Device images should degrade to an inline SVG fallback when a template asset is
  missing or fails to load, so long-running trace animation remains understandable.
- Timeline state chips stay compact for short traces and become horizontally scrollable
  for long traces; do not shrink long timelines until state buttons become unreadable.
  The range input, number input, state chip row, and direct timeline-track click are
  equivalent navigation surfaces and must remain in sync.

Async pattern:

```ts
import boardApi from '@/api/board';

const sleep = (ms: number) => new Promise(resolve => setTimeout(resolve, ms));

const submittedTask = await boardApi.verifyAsync(request);
const taskId = submittedTask.id;
let task = submittedTask;

while (true) {
  task = await boardApi.getTask(taskId);               // includes progress + progressStage
  if (task.status === 'COMPLETED' || task.status === 'FAILED' || task.status === 'CANCELLED') {
    break;
  }
  await sleep(2000);
}

// cancel: await boardApi.cancelTask(taskId);
```

Use a serial `while`/`await sleep` loop rather than `setInterval(async ...)` so polling
does not re-enter while a previous request is still in flight. Keep any page-level
`isVerifying`/loading state alive until this loop exits, including the cancelled and
failed terminal states.
When a board view unmounts, stop client-side polling and skip any late state writes.
The backend task should keep running unless the user explicitly calls the cancel API.
For a background-task UI, do not trap the user inside the submit panel. Allow the panel
to close and keep a global mini task indicator visible while active tasks exist. Run
history has two user layers, not three peer artifact tabs:

1. **Task Status** uses `GET /api/verify/tasks`, `GET /api/simulate/tasks`, and
   `GET /api/fuzz/tasks`. These lists contain active work plus failed/cancelled tasks
   that produced no result. A failed read retains diagnostics and Retry; previous rows
   are marked unrefreshed, and failure is distinct from a successful empty list.
   Board's `loadTaskInbox()` returns `true` only after that request applies the current
   authoritative lists; failed or superseded reads return `false` and cannot acknowledge
   unread failed-task notifications.
2. **History Results** combines `GET /api/verify/runs`, `GET /api/fuzz/runs`, and saved
   simulation runs from `GET /api/simulate/traces`. One completed run is one top-level
   history item. Failed sources retain their declared cause, fields and Retry in the
   history panel without a duplicate message; a failed source is not an empty list.
   Superseded list reads cannot acknowledge completed-result notifications or reconcile
   an open result against the old lists. Hidden result reads still announce failures
   when the panel cannot present them.

Completed work is excluded from the task-status lists. Verification counterexamples are
nested evidence under their owning verification result and keep replay/fix actions when
`historyPersistence.status` is `SAVED`, both persistence identity fields are present, and
the trace's `verificationTaskId` equals that result's `runId`;
they are not presented as independent runs. Show `violatedSpecCount` separately from
`counterexampleCount`, because a violated property is not guaranteed to have a
parseable/replayable trace. Failed and cancelled task rows can be dismissed through the
task `DELETE` endpoints; deleting a verification result deletes all of its linked
counterexamples.

When a verification or simulation task first reports `COMPLETED`, its result/trace detail
may become readable a moment later than the task row. The Board retries transient detail
failures for up to three attempts. A permanent client/auth/not-found error fails fast; after
transient recovery is exhausted, the UI preserves the task as completed, refreshes history,
and reports that the result is unavailable instead of reverting to a running spinner or
inventing an empty result. An immediate synchronous verification whose separate history
write failed or remains outcome-unknown can still replay its in-memory counterexample, but
must not expose automatic fix because `/api/verify/traces/{id}/fix` has no confirmed
persisted trace identity to address. For an unknown outcome, refresh history instead of
automatically rerunning verification.

Exploration findings are likewise nested under their run, but they are candidate
finite paths rather than NuSMV counterexamples. `FOUND_VIOLATION` is shown as candidate
evidence, `BUDGET_EXHAUSTED` stays neutral and explicitly says it is not a proof, and
`INCONCLUSIVE` names the eligibility/limitation boundary. Finding actions are replay and
formal verification only; never expose Fix for a fuzz finding.
The submit form mirrors the workload and target-selection guard owned by
[the exploration API contract](../api/fuzzing.md#submit-and-task-lifecycle).
Result UI localizes eligibility `reasonCode` and `limitations[]` codes; English backend
diagnostics are reserved for logs or advanced details.

History list calls are summary-only. `GET /api/verify/runs` and `GET /api/fuzz/runs`
already nest lightweight evidence summaries, so the Board must not also fetch global
full-trace/finding lists. Load full verification/fuzz run detail, full trace/finding
states, or full simulation states only when the user opens that result/replay. Keep
`dataAvailable=false` rows visible with a
damaged-history explanation and deletion action, but disable open/replay/fix; one bad
summary row must not turn the entire history panel into "load failed". A summary marked
available does not pre-validate its omitted full trajectory. If the on-demand detail read
rejects damaged states or frozen rule evidence, keep the history row, close no unrelated
result, and report that exact detail as unavailable rather than fabricating an empty replay.
When the backend identifies that persisted semantic corruption with
`PERSISTED_SEMANTIC_DATA_INVALID`, replace the affected local verification trace or run,
simulation result, exploration run, or (when `recordType=FuzzFinding` identifies one)
individual exploration finding with its unavailable placeholder before showing the
explanation. Do not make that permanent change for transport, authorization,
or service-availability failures: those outcomes remain retryable and must not hide
potentially valid evidence. When that failed detail was requested through a Board run
deep link, strip the dead run/trace/finding parameters as well, so refresh cannot reopen
the same unavailable artifact. Preserve an explicitly confirmed unavailable placeholder
across history-list refreshes for the mounted Board session; a new Board session may read it
again after an external data repair.
For a run URL that names a child trace or exploration finding, verify that the child belongs to
the declared run before replaying it. Clear only a confirmed absent, forbidden, malformed, or
persisted-invalid target; a transport, rate-limit, or 5xx failure keeps the URL so the user can
retry rather than losing the result they asked to open. When a target is confirmed unusable, close
any already-open parent result as the URL is stripped so the Board and its persistent explanation
describe the same state.
Task-inbox list calls likewise return closed summaries; full task diagnostics stay on the
per-id polling/detail endpoints.
Exploration history requests one bounded page at a time and exposes a localized Load
More action in the exploration filter. Appends are de-duplicated by run ID; deleting a
run resets offset pagination to the first page so shifted rows are not skipped. Exact
page bounds remain owned by [the exploration API](../api/fuzzing.md#run-results).
The Board also reads the current exploration model fingerprint. Fingerprinted history
must have a matching current fingerprint before it is treated as unchanged, so a
temporary fingerprint-read failure is shown as unconfirmed rather than silently falling
back to equal counts. Exploration rows without a valid fingerprint are rejected as damaged
development data rather than rendered with a weaker comparison.
Device, rule, specification, environment, and template snapshot replacement invalidates
the cached fingerprint immediately. In-flight fingerprint responses are revision-guarded,
so an older response cannot mark a newly edited Board as matching historical work.
Invalidation also withdraws that request's loading state. If the subsequent Board refresh
fails before a new fingerprint request starts, the comparison remains unavailable;
it does not keep reporting an obsolete request as running. A newer request owns its own
loading state, which an older response cannot clear.

Treat task `status` as execution lifecycle only. A task's direct polling endpoint may
return `COMPLETED` so the submitter can obtain the result, but the history UI then moves
that work to History Results. A violated verification must remain visually red, a
satisfied-but-incomplete run amber, and a complete simulation must say that all rules
entered the generated model rather than imply that the home is safe.
When a task is already being watched through the 1s per-task polling loop, pass
`excludeTaskIds` to the summary-list refresh so the inbox does not fetch and merge the
same task redundantly; keep the locally watched task in the list until the watch loop
ends.
Render the localized `progressStage` returned with each task instead of deriving a phase
from its percentage or elapsed time. Verification, simulation, and exploration stages
are persisted with the percentage by the backend, so one task-detail poll is both more
consistent and cheaper than separate progress and detail requests.

User-facing verification modes should distinguish behavior, not implementation jargon:
synchronous `verify` waits on the current page and returns the result directly;
asynchronous `verifyAsync` creates a pollable task with progress/cancel controls and
task-backed results. The Board defaults verification and simulation to background mode
because it preserves navigation, exposes real phases, and supports cancellation; the
synchronous option remains available when a user deliberately wants to wait in place.
Before verification, simulation, or exploration captures immutable input, the Board waits
for pending fix reconciliation and its mutation queue to drain, then rechecks the current
devices/rules/specifications. All three run kinds send only their run options; the backend
captures the saved Board.
A run includes edits whose save was already started; later edits cannot rewrite the
captured run snapshot.

Enable the attack option only when the current scene contains an automation rule or a
device-template reading with `FalsifiableWhenCompromised=true`. Otherwise explain that
the scene has no attack surface that can change model behavior; the backend rejects the
same no-op configuration with `422`.
If later Board edits reduce that attack surface below the selected budget, preserve the
user's value and mark the run configuration invalid. Do not silently clamp the budget or
turn attack modeling off; require the user to choose the new limit (or disable the mode)
before submission so the request still expresses their deliberate intent.

Treat attack configuration as run-local, not as a property of the canvas. Verification
offers `ANY_UP_TO_BUDGET` (all modeled combinations up to the chosen limit) and
`EXACT_POINTS` (only the checked device/rule-link points). Simulation offers only
`EXACT_POINTS`; it must never randomly choose compromised points behind the user's back.
Automation-link selections use persisted rule ids, so unsaved rules remain visible but
unavailable for exact selection. Result/history UI reads
`modelSemantics.attackSelectionPolicy` and `selectedAttackPoints`: exact runs name the
chosen device ids/rule ids, while only exhaustive runs are described as a budget.

Device trust/privacy labels use template declarations by default. Ordinary device
creation sends no label override; advanced settings may explicitly override the current
state or a local variable. Choosing "use template default" removes that override instead
of copying the current template value into the device instance. Existing imported or
persisted explicit overrides remain authoritative. A **shared** value's labels are not a device
override at all: they are scenario-wide, owned by the Environment Pool, and edited in the inspector's
advanced details — the device dialog neither shows nor accepts them
([scope rule](../architecture/shared-value-semantics.md#the-scope-of-a-security-label)).

Verification results and completed async verification tasks include `outcome`,
`modelComplete`, `disabledRuleCount`, `skippedSpecCount`, and item-level
`generationIssues`; technical warnings also appear in `checkLogs` with
`[rule-disabled]` / `[spec-skipped]` markers. UI code must surface every issue label and
reason even
when `outcome === 'SATISFIED'`, because they mean the generated SMV model omitted or
degraded part of the requested rules/specs. Present `INCONCLUSIVE` separately from
`VIOLATED`; there is no boolean `safe` shortcut because it collapses completeness and
property outcome into a misleading binary.
`modelComplete` is authoritative and required on interpretable completed results. The UI
must not reconstruct it from zero warning counts; a missing value is an incomplete
response and must never be promoted to a green complete-model state.

Every accepted verification/simulation task and every run result/trace carries
`modelSnapshot`: capture time plus device, rule, specification, effective environment,
and distinct referenced-template counts. `templatesFrozen=true` means generation used
the manifests captured at submission even if the template catalog changed while an
async task waited. It does not mean the current Board still matches. The UI may compare
a same-tab submission signature with current modelable input; historical/reloaded runs
must instead say that the current Board was not compared and scope the result to the
saved snapshot.

Completed async verification tasks also expose `specResults` and `nusmvOutput`,
matching synchronous verification results. Completed async simulations expose their raw
NuSMV output through the persisted `SimulationTraceDto` referenced by
`simulationTraceId`.

Verification traces return a structured verification-time `violatedSpec`; raw
`violatedSpecJson`, ownership ids, and request snapshots remain backend-internal.
Verification `specResults` is an array of `{ specId, templateId, specificationLabel,
formulaPreview, formulaKind, outcome, expression }` objects for the specifications
actually emitted to NuSMV. Render `specificationLabel`/localized `templateId`,
`formulaPreview`, and `formulaKind` in the ordinary result row; keep technical `specId`
and the actual NuSMV `expression` in an expandable technical section. These fields are
captured from the submitted run, so never look up the mutable current Board by `specId`
to rebuild a historical result. The array order is verifier-emission order, not
necessarily current canvas order. A completed verification can be `outcome='INCONCLUSIVE'` with an
empty `specResults` when generation emitted no specifications, or with
`outcome='INCONCLUSIVE'` entries when NuSMV produced no trustworthy result for an
emitted property; do not present either case as a property violation or as "all
specifications satisfied".

`verify` and `verifyAsync` require a saved board with at least one specification.
Simulation remains the no-spec workflow. Both submit run parameters only; the server
captures the scene. Task and history comparison use the
[authoritative model identity](../api/board.md#current-model-fingerprints); no browser-only
scene signature determines whether a persisted result still describes the current Board.

Contracts for board storage and board recommendation endpoints live in
[../api/board.md](../api/board.md). Contracts for verification, traces, and fix live in
[../api/verification.md](../api/verification.md).

Template manifests submitted through `addDeviceTemplate` must match
`backend/device-template-schema.json` exactly. The frontend may normalize user-uploaded
JSON into the PascalCase schema shape before submission, but it should not send
lower-case aliases or extra fields and should keep `API.Trigger` as `null`; transition
conditions belong in `Transitions[].Trigger`. The backend validates the raw manifest
before DTO mapping, so schema violations return a `400` instead of being silently
ignored. Every `InternalVariables[]` item must explicitly set `IsInside` and
`FalsifiableWhenCompromised`; `IsInside=true` is device-local and `false` is scene-shared,
and omission is rejected rather than silently selecting shared scope. Each variable also
declares `Values` (including `TRUE/FALSE` for booleans) or complete numeric bounds; an
omitted domain is invalid. `FalsifiableWhenCompromised` controls whether a compromised
instance may falsify that reading within its declared domain. API presence is not a
sensor/actuator classifier.

For custom-template icons, the optional PascalCase `Icon` field may contain a
`data:image/...` URI for a self-contained JSON import. Remote URLs are not accepted.
If it is missing, UI code should render the bundled state icon when one exists and
fall back to a generated template icon rather than showing a broken image.

Assistant Markdown disables raw HTML. Links accept only relative URLs or `http`, `https`,
`mailto`, and `tel`; external links use `noopener noreferrer nofollow` with no referrer.
Remote Markdown images render as alt text instead of being fetched automatically.

---

## Simulation — `api/simulation.ts`

Default-export object: `simulate`, `simulateAsync (→ Promise<SimulationTask>)`, `getTasks`,
`getTask`, `cancelTask`, `simulateAndSave`, `getUserSimulations`,
`getSimulation`, `deleteSimulation`. Same unwrap convention as `board.ts`.
Like `verifyAsync`, `simulateAsync` resolves to the authoritative accepted task, whose
server-generated `id` is used for polling. If the promise rejects with validation or
service-unavailable errors, do not start polling.
Synchronous `simulate` and `simulateAndSave` also reject invalid requests instead of
returning a success-shaped empty result. Timeout, interruption, failed execution, and
zero parsed states use the same submit error path. Successful results, async tasks, and
saved traces carry `modelComplete`, `disabledRuleCount`, and `generationIssues`; show
reduced-model traces as incomplete and list every omitted automation with its reason.
Saved trace context is returned as derived `isAttack`, `attackBudget`, `enablePrivacy`, and
`modelSemantics`, not as a
raw request JSON blob. Describe every simulation as generated-model behavior, not a
physical-home prediction.
Treat `historyPersistence` independently from trajectory validity. Plain `simulate`
returns `NOT_REQUESTED`. `simulateAndSave` may return playable states with
`OUTCOME_UNKNOWN`; refresh saved-run history and warn that persistence could not be
confirmed, but do not discard the trajectory, claim it was saved, or retry automatically.
Synchronous verification uses the same contract: preserve the formal conclusion while
presenting its separate run-history persistence status. Offer automatic Fix only when the
status is `SAVED` and the trace's `verificationTaskId` matches that status's `runId`.
Every result/history Fix entry additionally requires an `UNCHANGED` comparison of the
frozen model with current Board fingerprints. Loading and unavailable comparisons keep
their explanation visible beside the result; they do not admit repair. Replay continues
to use the frozen scene regardless of that comparison.
For users, present simulation modes as "preview now" versus "save in background": plain
`simulate` previews immediately and does not persist a trace, `simulateAndSave` previews
and stores the synchronous trace, and `simulateAsync` stores the trace automatically when
the task completes. A background simulation completion should leave the task-status
layer and update saved-run history instead of forcibly opening the timeline over the
user's current work.
The `1..100` simulation-step control exposes synchronized decrement/increment buttons,
direct integer entry, and a range slider. Normalize direct input at the control boundary;
all three surfaces must submit the same validated step count.
Compact run-setting panels keep long attack-selection and trust/privacy model explanations
behind accessible hover/focus/click info tooltips. Keep state-specific limitations and
errors visible inline: no modeled attack effect, the 50-point run cap, invalid budget or explicit point,
forced privacy propagation, disabled rules, and incomplete-model outcomes must never be
hidden as optional help.

## Rule recommendation — `api/rules.ts`

This module owns **rule recommendation only**: named exports `recommendRules` and
`cancelRecommendRules` (cancellable via `cancelRecommendRules()`), plus a default-export
object of the same two. **Rule persistence is not here**: targeted `getRules`/`addRule`/
`removeRule` methods live on `boardApi` (`api/board.ts`), which owns the `RuleDto` to
`RuleForm` mapping.

---

## Chat (SSE) — `api/chat.ts`

Named exports. Session management (`getSessionList`, `createSession`,
`getSessionHistory`, `markSessionTerminalSeen`, `getSessionActivity`, `requestSessionStop`,
`deleteSession`) uses
axios; **streaming does not**.
`getSessionHistory` returns `{ messages, nextBeforeId, hasMore }`; the first call requests
the newest bounded page, and `ChatView` passes `nextBeforeId` back as `beforeId` when the
user loads older messages. Older pages are prepended while preserving scroll position.
If the initial history request fails, the selected session remains selected and the message
composer stays disabled behind one persistent error with its cause and an explicit Retry
action; the UI does not reinterpret a failed read as an empty conversation or add a
duplicate toast. New-session clicks are single-flight, and a failed
or incomplete create response is shown without closing the mobile session list or detaching an
existing live conversation. The current SSE transport is detached only after the destination
session has been created successfully. A later explicit row selection made while creation is
pending takes precedence; the new row is retained but does not steal focus when its response arrives.

`sendStreamChat(...)` uses the native `fetch` API against
`${VITE_API_BASE_URL || ''}/api/chat/completions` (relative by default, so it also goes
through the proxy), sends a per-request `turnId`, and reads the response body with
`response.body.getReader()` (SSE).
This is the one place the axios
instance and its interceptors are bypassed — the `Authorization` header must be set
manually here. Protocol detail: [../api/chat-sse.md](../api/chat-sse.md).

`onProgress` appends verifiable execution events instead of replacing one generic
loading label. `ChatView` renders a full-width activity record with context loading,
task resumption after confirmation, every planning round, localized tool actions, tool
outcomes, cumulative success/failure/unconfirmed counts, duplicate-loop protection, and
final-answer generation inside the active assistant message. A resumed-task event shows
the bounded original user objective supplied by the backend; it does not expose model
reasoning. The record remains attached to that message and is persisted with the terminal
assistant row. History reload replaces the local response only when that row has the same
`turnId`, so an older completed turn cannot erase the current request.
The wrapper invokes `onAccepted` after successful HTTP `2xx` response headers, before body
parsing. This means the request crossed the synchronous HTTP-rejection boundary, so optimistic
local state must not be removed as though admission had definitely failed. It is not proof
that worker dispatch succeeded: a `2xx` stream may carry the admission-outcome-unknown warning
when compensating cleanup could not be confirmed. A later missing-body, invalid-frame, or
incomplete-stream failure waits for idle settlement, reconciles Board state, and replaces the
optimistic turn with authoritative history, including a valid user-only result. An HTTP
rejection before acceptance removes optimistic placeholders and restores the ordinary text draft.

`getSessionList` returns `ChatSession.active`, `latestTerminalMessageId`,
`latestExecutionStatus`, and `hasUnreadUpdate`. `ChatView` refreshes this list whenever the panel becomes visible and whenever
the document or window returns to the foreground. It reconnects
the selected conversation first when that session is active; when there is no selected
conversation it opens and monitors one active session. Every active flag in the refreshed list
participates in the global full-scene replacement/clear and playback guard, so a second concurrent
session cannot be hidden by list order or briefly unlock those shared-Board operations while the
monitored session settles. The selected conversation
alone owns the composer lock: switching away from a running conversation detaches its SSE
transport without sending Stop, and a new conversation can start while the first execution
continues under its own session. After `ChatView` mounts, it owns one complete session-list poll
loop even while hidden: five-second polls discover work started in another tab while idle, and the
interval shortens to one second while any row is active. Before that first mount, `App` owns the
five-second projection instead. This single-writer handoff prevents duplicate completion
reconciliation. Background completion is therefore detected even when no SSE connection is
attached. Polling and the foreground session-list refresh
share the same terminal-message completion path: the client reconciles the Board and shows a completion
notice; opening that row later loads its persisted
terminal assistant record and execution trace. Each row retains its latest terminal-status label;
an unread label remains after a tab or browser is closed because it comes from the persisted
session seen cursor, not an in-memory active-to-idle transition. The application also loads the
session list while the panel is closed, restores the active-session count into the same shared
Board guard, and shows running-conversation and unread-conversation counts independently on
the assistant entry button. This makes a still-running task visible and keeps full-scene operations
protected after a browser reload or in a second tab before the chat panel has ever been opened.
While the panel is closed, the application also refreshes this projection periodically and on
foreground/focus transitions. It retains each session's active state and exact latest terminal id,
not only aggregate counts: a newly observed terminal id, or a previously active id becoming idle or
disappearing without one, makes the application perform the same full Board and run-history
reconciliation used by an attached chat view. Exact terminal identity covers work that starts and
finishes between two polls. This also covers a second tab whose chat panel has never been mounted and
therefore never received the original stream or its refresh commands.
If the refresh fails, one shared reconciliation-required state keeps the assistant entry visibly
marked, exposes the existing retry panel when opened, and keeps full-scene replacement, clear, and
playback guarded until a retry succeeds. Poll responses are discarded if create, delete, or
foreground refresh changed the session-list version while the request was in flight, preventing an
older snapshot from removing or restoring rows. Foreground refresh and background polling do not
run concurrently, and create responses merge by session id when polling observed the commit first.
Selecting a row already marked active starts activity polling before history loading,
so a slow or failed history request cannot briefly unlock known-running work; rows without
that signal perform the dedicated activity check. This is an authoritative activity
reattachment rather than an SSE replay: the UI shows that server work is still running,
exposes the real stop endpoint, and keeps mutations locked. Once activity becomes idle it
reloads the terminal assistant row (including the persisted trace) and reconciles the
Board/run history. Terminal history/session refresh still runs when Board reconciliation
fails; the reconciliation retry remains visible and locked, but the completed assistant
record is not hidden behind an unrelated Board refresh failure. Session-list running/status labels
make runs started in another tab and results completed while offline visible without waiting for a
conflicting action to fail.

History rendering acknowledges only the newest terminal assistant message id actually present in
the visible conversation. A hidden panel, background document, list refresh, or history GET does
not mark a result seen. The acknowledgement is monotonic and carries the exact loaded id, so a
later result from another tab remains unread. If acknowledgement succeeds but the following list
refresh fails, the UI keeps the unread marker until an authoritative refresh rather than claiming
that all results were seen.

The chat stop control first calls `requestSessionStop(sessionId, turnId)` and waits for the
durable stop fence before aborting the SSE response or polling activity. Reattached work
without a locally known turn id sends `null`; an ordinary local request must send its opaque
turn id so a quick Stop cannot miss pre-admission work or stop a newer turn. A pre-admission
fence is valid for two minutes and the backend retains at most 64 live fences per session;
expired entries cannot stop a later request that reuses an old turn id. Stop cannot undo a
backend tool call that already started, but the backend still persists a
result that returns after the stop request before ending the workflow. `ChatView`
therefore polls `getSessionActivity` until the cross-instance execution lease reports
idle, keeps assistant mutations locked, then reloads conversation/board/history.
The terminal transaction rechecks the durable stop flags while holding the session lock, so
the acknowledged stop remains `STOPPED` even when the subsequent browser abort reaches the
worker before its next control poll.
The settlement history request invalidates any older in-flight initial history load, and a
successful settlement clears the earlier history-error state. A delayed response therefore
cannot replace a newer terminal row or leave the composer disabled after recovery.
Explicit Stop and logout wait for durable stop settlement. Logout first refreshes the complete
session list, so it includes work started in another tab; the application shell performs the same
stop/wait/reconcile sequence if the lazy chat panel was never mounted. The logout API then writes
an account-scoped stop fence before revoking the token as a final server-side backstop. Session
switching and new-conversation creation only detach the local transport, so they do not cancel the
requested operation. Deleting an active row is disabled in the client and rejected by the server
with `CHAT_SESSION_BUSY`, leaving the work visible.
Activity checks have their own 2.5-second request timeout, so three consecutive query failures
produce an outcome-unknown warning and an authoritative state refresh within seconds rather than
inheriting the general 100-second axios timeout. Do not label a navigation detach as cancelling
the requested operation.
If the server continues to report an active tool for the 10-second settlement window,
the UI switches to the same visible retry/locked state instead of spinning for the full
SSE timeout or releasing a known-running mutation.

Stream commands cross the component boundary through a promise-returning
`executeCommand` callback. `ChatView` does not release its global interaction lock until
the Board confirms the refresh. A failed targeted refresh falls back to `board_state`;
if that also fails, a localized retry panel keeps assistant requests, scene replacement,
and trace playback locked until reconciliation succeeds. Sign-out invokes the same
settlement through the exposed `prepareForLogout()` method, requiring explicit user
confirmation when the backend tool outcome remains unknown. SSE `401` responses clear
authentication and navigate to login only when the stream's original token is still current;
a delayed stream from another account is isolated. `403` responses remain authorization
errors and do not discard a valid login session.
Board collection refreshes triggered by chat are serialized through the same mutation
queue. A pending scene replacement disables new assistant requests. Read-only trace
playback still locks Board mutations, but it does not disable the assistant composer;
sending a new assistant turn first closes the visible trace/simulation playback and then
starts the request. Starting playback or scene replacement while an assistant stream is
active remains blocked until the stream stops and its authoritative refresh can complete.

`onFinish(terminal)` is emitted only after one valid persisted terminal acknowledgement,
never merely because the reader reached EOF and never on abort. Server failures use a
structured `error` frame, so model-authored `content` is never classified by a text prefix. A persisted server-error
terminal reaches `onFinish` before the corresponding `SERVER_FRAME` reaches `onError`.
Missing-body, invalid-frame, and incomplete-stream failures retain the accepted local turn
until idle settlement reloads authoritative history and Board state. `ChatView` assigns
epochs to streams, session-history loads, and session-list
loads; callbacks from replaced requests cannot clear a newer controller or overwrite a
newly selected conversation. Conversation-history loading is separate from response
generation, so the Stop control is shown only for a response that can actually be
aborted.
Unmount or browser teardown aborts history/activity/settlement transports and clears elapsed/poll
timers. It does not send the explicit Stop command: accepted server work continues under its session
lease, and its persisted terminal result is restored as unread on the next application load.

---

## Types

Backend↔frontend field alignment worth noting:

- `types/spec.ts` `SpecCondition` (`side`, `deviceId`, `deviceLabel`, `targetType`,
  `key`, `relation`, `value`) and `Specification`
  (`aConditions`/`ifConditions`/`thenConditions`/`devices`) match the backend
  `SpecConditionDto` / `SpecificationDto` shape. For board storage, spec `deviceId`
  fields are raw board node ids. The backend converts them to normalized `varName`
  values in its captured scene. The frontend compares returned model identities rather
  than independently rebuilding a model fingerprint.
  `deviceLabel` is only a display snapshot and is not
  used for resolution. See
  [../architecture/device-identity.md](../architecture/device-identity.md).
- Spec `formula` and `devices[]` are derived display caches, not authority. Manual
  creation, AI recommendation apply, and standard scene import rebuild both from the
  accepted structured conditions; they do not trust an AI/file-authored cache. Call
  `buildSpecFormula(spec, { nodes, deviceTemplates })` so the preview mirrors NuSMV
  state tuples, environment variables, and trust/privacy resolution. `board.ts`
  `toBackendSpecificationWriteDto` strips `templateLabel`, `formula`, `devices[]`, and
  condition display/UI fields from targeted and batch writes. Run requests submit options
  only; the backend captures those persisted structured conditions. Only a run result's
  checked expression says what the checker actually evaluated.
- `types/verify.ts` holds `VerificationRequest`, `VerificationResult`,
  `VerificationTask`, generation warning counts, and the trace types.
- `types/fix.ts` holds fix-related types.
- **Runtime option lists** (`relationOperators`, `targetTypes`, `specTemplateDetails`)
  live only in `assets/config/specTemplates.ts` — that is the single source components
  import. Do not reintroduce copies in `types/spec.ts`.
- Simulation, verification, and exploration use the shared `TraceState` tree in
  `types/verify.ts`, including `TraceVariable.observed` and nullable security labels.
  Their result and evidence types remain distinct. `board/simulationResult.ts` adapts a
  saved trajectory for playback and projects its history summary without dropping artifact
  availability, frozen context, or the original initiator and timestamp.
- Live Board responses and frozen playback rules share `RuleDto` in `types/rule.ts`.
  `RuleForm` remains an editable draft with string UI identities and source/target fields.
  Complete run results are validated at the API boundary; presentation uses their supplied
  outcomes, omission counts, and step counts without deriving replacements from logs or traces.

When a backend DTO changes, update the corresponding `types/*.ts` and this guide in
the same PR (see [../../CONTRIBUTING.md](../../CONTRIBUTING.md)).
