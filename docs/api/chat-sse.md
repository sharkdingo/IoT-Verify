# Chat API (SSE Streaming)

Contract for `/api/chat` — session management plus the streaming completion endpoint.
Session endpoints use the standard `Result<T>` envelope ([overview.md](overview.md));
the streaming endpoint does **not** — it is an SSE stream.

Verified against code on 2026-10-23. Source: `controller/ChatController.java`,
`service/impl/ChatServiceImpl.java`, `dto/chat/`, `frontend/src/api/chat.ts`,
`frontend/src/App.vue`, and `frontend/src/components/ChatView.vue`.

---

## Session management (`Result<T>`)

| Method | Path | Body / Response | Notes |
| :--- | :--- | :--- | :--- |
| GET | `/api/chat/sessions` | → `ChatSessionResponseDto[]` | List the user's sessions |
| POST | `/api/chat/sessions` | → `ChatSessionResponseDto` | Create a session (no body) |
| GET | `/api/chat/sessions/{sessionId}/messages?beforeId=&limit=50` | → `ChatHistoryPageDto` | Bounded message history, newest page first |
| POST | `/api/chat/sessions/{sessionId}/seen` | `{ terminalMessageId: Long }` → `null` | Advance the session's seen cursor to the exact terminal assistant message rendered by the client |
| GET | `/api/chat/sessions/{sessionId}/activity` | → `ChatSessionActivityDto { sessionId, active }` | Authoritative cross-instance check for whether a request is still active for the session |
| GET | `/api/chat/sessions/{sessionId}/confirmation` | → `ChatPendingConfirmationDto { sessionId, actions }` | Server-authoritative protected actions waiting for an explicit UI decision |
| POST | `/api/chat/sessions/{sessionId}/stop` | `{ turnId: String \| null }` → `null` | Stop the named local turn, or the reattached active response when its turn id is unavailable; already-started writes are not rolled back |
| DELETE | `/api/chat/sessions/{sessionId}` | → `null` | Delete a session |

`ChatSessionResponseDto`: `{ id: String, userId: Long, title: String | null, createdAt,
updatedAt, active: boolean, latestTerminalMessageId: Long | null,
latestExecutionStatus: ChatExecutionStatus | null,
hasUnreadUpdate: boolean }`. `active` is computed from the same renewable database
lease as the dedicated activity endpoint, allowing a new browser connection to discover
an already-running session from the list response.
`latestTerminalMessageId`, `latestExecutionStatus`, and `hasUnreadUpdate` are projections of the
latest persisted terminal assistant row, not a second completion record. The session stores only the monotonic
`lastSeenTerminalMessageId` cursor. `hasUnreadUpdate` is true when the latest terminal id is newer
than that cursor. A session with no terminal assistant row has `latestExecutionStatus=null` and
`latestTerminalMessageId=null` and `hasUnreadUpdate=false`.
The seen endpoint validates that `terminalMessageId` is a terminal assistant row in the owned
session, then advances the cursor without allowing it to move backwards. Clients acknowledge the
exact id loaded in history; they must not acknowledge "whatever is latest". This leaves a newer
terminal result unread when another tab finishes it after the first tab loaded history. Session
list and history GETs have no read side effect, and a hidden panel or background document must not
call the seen endpoint.
Clients may use the list's `active` projection before mounting a conversation view to restore a
global running-session indicator and shared-Board safety guards after a reload or in another tab.
Reading `active` does not attach to, cancel, or otherwise change the running execution.
Before the chat panel is first mounted, `App` owns the periodic session projection. After
`ChatView` mounts, it becomes the sole owner even while hidden: it polls the complete list every
five seconds while idle and every second while any session is active. This single-writer handoff
prevents two reconcilers from clearing each other's pending state while still discovering work
started in another tab. At lazy mount, `App` transfers its account-scoped active/terminal
observation baseline and any in-flight reconciliation to `ChatView`. The first list response
is compared against that baseline, so work finishing during component loading is still
reconciled. The child waits for the prior reconciliation before taking over unresolved work;
account changes discard the handoff. Both owners retain each session's active projection and
latest terminal message id. A newly observed terminal id, or an active session becoming inactive without one,
triggers a full Board and run-history reconciliation even if the entire turn completed between
polls or no tab retained the original SSE stream. A failed reconciliation remains a distinct
visible and blocking client state and is retried; it is never inferred as successful from the
terminal or unread projection.
`title=null` means the session has no user-derived title yet; clients render their own
localized "new conversation" label. Persistence placeholders such as `New Chat` are not
part of the user-facing contract and are normalized to `null` when reading older rows.
When a session is first given a title, the backend folds all Unicode whitespace runs to a
single space, trims leading/trailing whitespace, and keeps at most 12 Unicode code points
before appending `...`; it does not split a surrogate pair.
At most 100 sessions are stored per user. Creation beyond that limit returns `400` and asks
the user to delete an old session; the list endpoint is correspondingly bounded to the 100
most recently updated sessions.
Each session also has a configured stored-message ceiling. Before accepting a completion,
the backend reserves enough rows for one worst-case complete tool turn. A conversation too
close to the ceiling returns `429` before claiming a lease with
`data={ reasonCode: "CHAT_HISTORY_LIMIT_REACHED", messageCount, maxMessagesPerSession,
requiredTurnCapacity }`.
`ChatHistoryPageDto` is `{ messages: ChatMessageResponseDto[], nextBeforeId: Long | null,
hasMore: boolean }`. `limit` defaults to 50 and accepts `1..100`; `beforeId` is the positive
message-id cursor returned by the preceding page. The service scans at most 2,000 raw rows
per request while hiding internal tool messages, so a tool-heavy turn cannot turn one history
read into an unbounded allocation.
`ChatMessageResponseDto`: `{ id: Long, sessionId: String, role: "user" | "assistant", content:
String, turnId: String, createdAt, executionTrace?: ProgressDto[], executionElapsedSeconds?: Integer,
executionStatus?: "COMPLETED" | "AWAITING_CONFIRMATION" | "PARTIAL" | "STOPPED" |
"DISCONNECTED" | "FAILED" }`.
The frontend accepts only the paginated object above; obsolete bare message arrays and
internal `tool` rows are rejected. It treats this as an untrusted boundary: `role`,
`sessionId`, `content`, numeric ids/counters,
history cursors, execution-status values, and every nested progress stage/outcome must have
the documented shape. A malformed page is rejected and remains unavailable rather than being
rendered as a completed or verified turn.
Once a worker owns its admitted turn, normal, provider-failure, and detached-transport paths
attempt to save one visible terminal assistant record. Admission cleanup, pre-execution
ownership loss, or a terminal write failure can honestly leave no assistant row. A terminal
write, including execution-trace serialization, is attempted at most once. If it fails, the
server sends a structured `error` frame and closes without a terminal acknowledgement; it does
not retry the write as a misleading disconnect row. After any accepted-stream failure, the
client waits for the session to become idle and replaces optimistic state with authoritative
history, including the valid user-only outcome. When saved, the terminal row stores
the exact bounded execution record, elapsed time, and explicit terminal status; `PARTIAL`
means no platform tool ran, or tool work began before a later failure, an execution guard,
or an uncertain result. The UI distinguishes a non-empty trace with no tool execution or
result activity as "No platform tools ran" rather than implying that an operation partially
completed. A trace containing only tool-start activity remains `PARTIAL`, because its outcome
may be unknown; an absent trace is never used to infer that no tool ran.
`COMPLETED` means at least one platform tool ran and the visible response stream and terminal
persistence completed; it does not prove that every requested platform objective completed.
History restores only a non-empty, structurally valid trace stored on that terminal row. It
never rebuilds a trace from hidden historical tool-call/result rows. If a row says
`COMPLETED` but its stored trace is missing, malformed, guarded, lacks a usable tool result,
or does not pair every tool execution with the same tool and round's result in order,
the response omits that status rather than presenting unproven success. An intermediate
`PARTIAL` result remains unresolved until a later paired `USABLE` result from the same tool;
another tool's success cannot recover it. `FAILED`, `RESULT_UNAVAILABLE`, and
`CONFIRMATION_REQUIRED` results cannot support `COMPLETED`. The frontend history
validator independently rejects a `COMPLETED` message without the same persisted usable-tool
evidence, and the component renders such directly supplied data as unconfirmed rather than
completed.
The session-list summary has no execution trace, so its `PARTIAL` indicator says "View
execution details" instead of guessing whether work partly completed. The message's trace
still distinguishes a zero-tool reply from partly executed work.
`AWAITING_CONFIRMATION` means a no-write preview is waiting for the user's decision.
`STOPPED` means the user explicitly requested the response to stop. `DISCONNECTED` is reserved
for a worker that loses durable execution ownership (for example lease or account cleanup),
not for a browser that leaves the SSE stream. Neither status implies rollback. A missing terminal status
remains unconfirmed rather than being inferred from tool records or prose. Raw tool JSON,
internal identifiers, provider exceptions, and private model reasoning remain hidden.

Only one stream request may be active for a session across all backend instances. A user may
have up to `CHAT_MAX_CONCURRENT_SESSIONS_PER_USER` admitted chat sessions running at the same
time (four by default); this is a resource guard, not a session-switch cancellation rule. Starting
another turn after the configured limit is reached returns `429` with the
standard `USER_CHAT_OPERATION_BUSY` envelope, while switching conversations or detaching the
SSE transport leaves the admitted session running.
A concurrent request or deletion returns `409` with
`data={ reasonCode: "CHAT_SESSION_BUSY", sessionId }`; it does not interrupt the first
request. Registration happens before the worker is queued, so the short enqueue window
cannot admit a second request. The active request id, expiry, and stop flags are stored on
the locked `chat_session` row. Activity checks and stop requests therefore remain
authoritative without load-balancer affinity. A scheduled heartbeat renews the short-lived
leases while their owning workers are registered; timing keys and defaults are owned by the
[configuration reference](../getting-started/configuration.md#llm-ai). Each pass snapshots the
local executions and renews every matching row in one transaction, locking rows in stable
session-id order and then checking the complete user/session/execution ownership tuple. After
all locks are held it recomputes the effective current time from both the database clock and
monotonic elapsed time. The batch extends only the same still-unexpired execution ids, so a
delayed or stale worker cannot revive an expired lease or overwrite a replacement lease.
Admission and the complete renewal batch also reject a commit whose round trip would leave less
than one configured heartbeat interval before expiry. Expired-row cleanup runs before renewal,
and the pass checks the margin again immediately before returning, so later sessions or cleanup
work cannot consume an earlier session's heartbeat safety margin.
Transient database failures are retried, but the local worker is cancelled once ownership
has remained unconfirmed for one complete database-lease TTL.
Lease creation, activity checks, renewal, release, and expiry cleanup all compare against
the database clock rather than a JVM clock. The execution id acquired before dispatch is
also carried through the queued worker and controller cleanup: a worker whose local
registration was replaced cannot start, and an older request's `finally` cannot remove or
release the replacement execution.
The execution id also fences persisted user/tool/terminal messages and the shared
confirmation, scenario-draft, and task-continuation state. Ordinary AI writes perform a
non-locking ownership precheck, then briefly lock and recheck the session row immediately
before commit. Long tool transactions therefore do not block the heartbeat, while a replaced,
stopped, or expired worker still rolls back instead of appending audit rows or overwriting
follow-up state.
The same scheduled pass clears expired lease ids and stop flags. A crashed or restarted
worker therefore releases its session after at most one TTL instead of leaving it busy for
hours, while a healthy execution can run past any single lease window. Normal completion
and queue rejection still clear the lease immediately.

Before dispatch, the backend locks the session, verifies capacity and per-session `turnId`
uniqueness, claims the execution lease, and persists the user message in one transaction.
Reusing a `turnId` in the same session returns `409`. If dispatch fails, or a queued worker
cannot confirm that it acquired the admitted execution, the exact admitted user row and lease
are removed before an HTTP rejection is returned. A `503` queue-saturation response is returned
only after that cleanup is confirmed. If cleanup cannot be confirmed, the endpoint instead
returns a `2xx` SSE response containing a localized structured `error` frame, closes
without a terminal acknowledgement, and requires the client to wait for the session to become
idle and reconcile history and Board state before retrying.

Across sessions, each user may run up to the configured assistant-stream limit. Redis
coordinates this admission across backend instances; if Redis is unavailable, the same
limit is enforced within each process. A token-checked heartbeat renews every live Redis
admission lease, so a healthy stream can run beyond its initial two-hour safety TTL without
another instance admitting a replacement. Controller cleanup releases the in-process slot
in a `finally` path even when database execution-lease cleanup fails. Excess requests return
`429` before a stream starts.
If token renewal proves that ownership was replaced, or Redis remains unavailable through
the complete lease TTL, the old worker is interrupted and its controller rechecks the lease
before returning a result. A brief Redis outage remains fail-open as documented in the
configuration reference, but an expired unconfirmed worker is not allowed to run indefinitely
beside a replacement.

The admission response includes `data.reasonCode=USER_CHAT_OPERATION_BUSY`, operation
kind, coordination scope, and limit. The frontend renders this as a wait-for-other-session
message that names the actual configured limit instead of exposing the backend's English diagnostic.

Closing the floating panel, switching conversations, closing a tab, or exiting the browser does
not request Stop and does not transfer execution ownership to the SSE connection. Browser teardown
ends the client transport; the backend treats emitter write failure as detachment, continues the
admitted worker under its database lease, and attempts the same terminal persistence as an attached
stream. On the next authenticated application load, the session list restores both active rows and
persisted unread terminal results. Explicit Stop, lease ownership loss, and account deletion remain
the cancellation boundaries.

Permanent account deletion is stronger than ordinary per-session activity handling. The backend
marks every persisted execution lease for that user as stopped and completes locally bound
emitters. Correctness does not depend on the local emitter optimization: each
session/message write locks the active user row and rechecks that the session is still owned by
that user in the same transaction. Database cascade constraints make this invariant independent
of the chat process: work committed first is removed with the account, while a user/session/task
write that arrives after deletion is rejected because its parent row no longer exists. A remote
or late stream therefore cannot recreate data after the account has been removed.

---

## `POST /api/chat/completions` — streaming (SSE)

Sends a user message and streams the assistant's visible reply. **Not** wrapped in
`Result<T>` — the response is `text/event-stream` produced by a Spring `SseEmitter`
(60-minute default timeout, configured by `CHAT_SSE_TIMEOUT_MS`).
Synchronous rejections that occur before the stream is established (for example invalid
input, an unknown session, or a busy session) use the standard JSON `Result<T>` error
envelope and retain their documented HTTP status even when the client sends
`Accept: text/event-stream`.

Every non-blank message first runs model-driven planning with the complete registered
tool catalog. The model may choose zero tools for ordinary conversation, or freely chain
read, recommendation, mutation, verification, and status tools when the request spans
domains. This decision is based on the message meaning and conversation context rather
than a keyword or deterministic intent route. Planning requests independent reads together
when their arguments are known, while dependent calls wait for their input results.
Tool calls and bounded tool results are
persisted as internal chat messages but are not exposed as raw user-visible text. A result
over `CHAT_MAX_TOOL_RESULT_BYTES` becomes a structured `TOOL_RESULT_TOO_LARGE` unavailable
result before persistence or reuse as provider context.
Structured progress frames expose the verifiable execution state and outcome of each
step while it runs. Public text streams during tool-aware inference as described under
[Stream frames](#stream-frames). For every turn, the backend buffers each
complete sentence before exposing it.
For Responses providers, refusal text deltas enter the same public-text stream as
ordinary output text. The final refusal and response-completion events do not replay
that text, so a refusal remains visible without duplicate content.
During a tool-aware round, a sentence that needs evidence not yet available is held with
the following text until the complete tool decision and its executions finish. Ordinary
explanations continue to stream immediately. Pending claims are then checked against the
same current-turn evidence guard; a response returning no tools still cannot assert a
current read or mutation. This prevents early narration from cancelling the inference
before its read call can provide evidence.
The reply prompt distinguishes guard overlap from different predicates and initial values,
retains complete specification conditions and temporal operators, and requires trace
explanations to follow observed state-index order.
Explicit tool-result or current-platform completion/mutation claims are checked against
the current turn's usable tool evidence. When the relevant read or mutation capability has
an authoritative usable result, the model's natural-language summary is preserved; the
deterministic `TOOL_RESULT` progress and action receipt remain the execution record. When no
such evidence exists, the unsupported segment is replaced with a deterministic warning.
When confirmed results can replace it, later checked prose remains available. Otherwise,
later provider prose is withheld; earlier safe segments remain visible. A claim hidden without
evidence downgrades an otherwise `COMPLETED` turn to `PARTIAL`. This
deliberately narrow check does not classify API documentation, historical descriptions, or
sample/draft content as a platform operation. Closed ASCII/typographic quoted spans, inline
backtick code, and backtick or tilde code fences are preserved, so explanations and translations
can discuss a claim without presenting it as an executed action. Fence closers may be longer than
their opener. While streaming, an unfinished literal delays sentence release; at end of stream,
an unclosed quote or fence is inspected as ordinary text so it cannot hide a later unsupported
claim.
Ordinary conversation finishes as `PARTIAL`, while its answer content remains available to the
user and is labelled as a zero-tool response rather than a partially completed operation.

Planning is objective-oriented rather than a rigid domain workflow. A delegated task may
combine targeted deletion, creation, environment, rule, specification, simulation, and
verification tools in the order supported by current state and tool results. A successful
tool call does not by itself end the task. Planning continues until the original objective
is complete or a confirmation, unavailable-result, no-progress, or emergency boundary
requires it to stop.

Questions about the current scene, including device/rule/specification counts, are
planned from `board_overview` rather than inferred from chat history. This includes
follow-ups about current links and specifications: read-only analysis permits read tools,
and a previous turn's snapshot is not evidence of current state. Discussion limited to
earlier findings must identify that historical scope. A request to extend
or complete the current scene reads that overview first and may compose targeted device,
environment, rule, and specification recommendation/mutation tools while preserving the
existing scene. Before adding a device, the planner reads `list_templates` for the exact
available template name instead of treating `board_overview` as a template catalog or
inventing a name. `recommend_scenario` remains a complete replacement/import draft and
is used only when the user explicitly asks for that workflow. When the user later asks
to apply that chat-generated draft, the planner calls `apply_scenario` directly instead
of reading the Board and deleting devices individually. The tool first returns a
no-write impact preview; after a later explicit confirmation it uses the same atomic
Board replacement authority as UI scene import. See [ai-tools.md](ai-tools.md) for the
stored-draft and expiration contract.

Tool execution is not one transaction across an entire user request. Each mutating tool
commits or rejects independently. AI-originated Board writes, verification/simulation task
creation and cancellation, synchronous verification-history persistence, and trace deletion
require the same unexpired execution id with no committed stop request. Ordinary write
transactions acquire the session-row lock only at their commit fence, so a replacement or
stop committed before that fence rejects and rolls back the mutation. Verification and
simulation cancellation are the exception: because they trigger an irreversible local process
stop before database commit, their short transactions lock and verify the session immediately.
Long NuSMV computation never holds the chat row lock. There is no five-round product budget: planning
continues while calls or results are changing. Two guards prevent runaway execution:
consecutive rounds that repeat the exact same calls and results stop after
`CHAT_MAX_STAGNANT_ROUNDS`. A round consisting entirely of usable task-status reads with
matching task ids and authoritative `PENDING`/`RUNNING` status resets stagnancy: an active
background job can legitimately remain at the same stage. Mixed rounds, failed or malformed
reads, and repeated terminal snapshots remain subject to the no-progress guard.
`CHAT_MAX_TOOL_ROUNDS` is a high emergency ceiling rather
than a normal task limit. One planning response is additionally limited by
`CHAT_MAX_TOOL_CALLS_PER_ROUND`; an oversized response executes none of its calls. These
guards preserve earlier commits, emit a visible guard
event, and still run the streaming final-answer model with an instruction to identify
completed, failed, and unfinished work accurately.

Account deletion removes confirmation, draft, continuation, chat history, and the account
inside the same database transaction. Only after commit does it stop local chat transport
and revoke the current token. A rollback therefore restores all durable account state.

`requiresUserConfirmation=true` is a generic no-write boundary, not only a deletion
preview. The planning loop stops immediately for destructive previews and for proposed
alternatives such as an available replacement device name. The assistant must state that
nothing was changed and wait for the user's choice; it cannot accept its own suggestion
in a later planning round of the same message.

That boundary pauses only the protected step. The backend keeps the original user
objective in a per-user, per-session server-side continuation entry for 15 minutes. On a
later explicit confirmation it restores both the live confirmation authority and the
original objective. The continuation also retains up to four recent user messages plus a
bounded, sanitized summary of the pending tool result. The latest user message is
authoritative when it changes or narrows the older objective. After the confirmed tool
returns a usable result, the planner resumes the remaining work with the complete tool
catalog. A targeted replacement can therefore preview and confirm a deletion, create the
replacement, repair dependent rules/specs, and run requested verification without treating
the confirmation as the end of the task. A second protected action still requires its own
preview and confirmation. Ordinary questions and task updates preserve the pending preview;
explicit cancellation, session/account cleanup, and expiration clear it. Continuation
state is stored in the shared database, so a normal backend restart or load-balanced
follow-up does not by itself discard a still-live entry.

If a model response contains several parallel tool calls and one call reaches this
boundary, later calls in that same response are not executed. The backend records an
explicit `skipped=true` tool result for each one so the provider conversation remains
protocol-complete and the final visible explanation can still stream. The same rule
applies after `RESULT_UNAVAILABLE` when `mutationMayHaveCommitted=true`; skipped calls are
not counted as successful or failed executions. A read-only unavailable result is recorded
but does not skip independent later calls in the response, because their read assumptions
cannot have been invalidated by that failed serialization.

History reconstruction also validates that every persisted assistant tool-call id has
exactly one matching tool-result id before sending that block back to a provider.
The bounded model-context window is selected by descending database message id and then
restored to ascending id order; per-instance JVM timestamps never determine protocol order.
Incomplete, duplicate, malformed, or isolated internal tool blocks are omitted from the
model context, while surrounding user-visible conversation remains available. This keeps
corrupt internal protocol records from being represented as executed work. During the active
request, blank or reused Chat Completions correlation ids are replaced with unique internal
ids before persistence or tool execution, and the same repaired ids are used for assistant
calls and their results. Responses rejects missing, whitespace-padded, or reused call ids
before dispatch: rewriting them would break the original opaque continuation items.
The planner explicitly treats the supplied conversation as a bounded recent window.
Absence from that window does not mean an earlier recommendation, preview, or edit
never happened. For conversation-wide operation audits it uses the paged
[`read_conversation_activity` tool](ai-tools.md#conversation-activity), whose evidence
comes from canonical stored operations rather than earlier assistant claims. An
unfinished page traversal must be described as partial coverage. Historical receipts
are not new mutation receipts and do not establish current Board state. Saved-run
lookups and exact model-page reads have bounded factual progress summaries, so a
guarded unsupported claim can be replaced by actual historical evidence rather than
stopping the remaining read chain. Nominal phrases such as "已保存的正式验证" are not
claims that this turn saved a result; actual unproven save/edit claims remain guarded.
When an unsupported statement can be replaced with tool evidence, the notice is
emitted after that reply round's remaining text as a separate paragraph. It is not
inserted into an unfinished Markdown table cell or sentence. Streamed and persisted
text use the same output path. Historical page progress separately identifies
explicitly recorded saved drafts, confirmation previews, and past changes from
available canonical entries; these counts are not new action receipts.
Scene-replacement confirmation replies also include a server-generated summary of
the environment inputs' declared control-source and privacy labels from the exact
pending preview. This summary remains present when model prose omits those choices;
it describes modeling assumptions, not a verified security conclusion.

Pending destructive confirmation data is not recovered from that bounded history. On an
explicit confirmation turn, the backend injects compact server-authoritative context for
the pending tool, target, and opaque token, so a large preview cannot force the assistant
to request the same confirmation repeatedly. Full-scene application likewise keeps the
draft and Board impact token server-side and injects only the instruction to call
`apply_scenario` with `confirmed=true`. The separate continuation entry supplies only
user-authored objectives/updates and sanitized tool output, not model reasoning, so
successful confirmation can be followed by the remaining requested tools even when older
chat detail fell outside the history window.

Protected mutation previews (deletions, formal-fix application, Board/history clear, and
bundled-default reset) additionally return
an opaque `impactToken`. The backend keeps one pending protected action per authenticated
user and chat session, bound to the tool, target, and canonical digest of the visible
preview. Confirmation is target-aware rather than position-aware: ordinary questions or
changed instructions may intervene without discarding the preview.

Protected authority never comes from model interpretation of ordinary text. The client
reads `GET /api/chat/sessions/{sessionId}/confirmation`, renders an explicit decision for
each returned `actions[]` item (`kind`, opaque `confirmationId`, `toolName`, and readable
`target`), and sends that item's `kind` and `confirmationId` plus `CONFIRM` or `CANCEL` in
the next stream request's structured `confirmation` field. The accepted kinds are `DESTRUCTIVE`,
`DEFAULT_TEMPLATE_RESET`, and `SCENE_REPLACEMENT`. The backend requires that exact kind and identity to
still be pending for the authenticated session; an invented, stale, or mismatched command
authorizes no write. The client rejects a confirmation response with a mismatched session or
any value outside this enum, missing identities/targets, or duplicate kinds instead of
rendering unknown authority. Replacing a pending preview invalidates old buttons, including
old cancellation commands. The approved identity remains attached to the execution and is
checked again when the tool consumes its pending action; the model cannot reuse a confirmed
kind to authorize a new preview in that turn. `DESTRUCTIVE` is the
existing wire-level group for an ordinary protected preview; the UI therefore labels it as
the previewed protected action rather than claiming every member is a deletion.
`target` is the readable label or description captured when that exact preview was issued.
Device, rule, specification, and template previews use their display label or semantic
description; saved-result and task previews include the saved timestamp and readable
result details so equal-sized executions remain distinguishable. The internal
stable target key remains server-side and is never replaced by this display text for
authorization. Reading confirmation state, including after reconnecting, does not re-read
the current object to change the previewed label. An obsolete pending payload without its
display snapshot is rejected and requires a fresh preview.
Semantically invalid protected-action records and scenario drafts grant no authority and are
decoded without writes in the confirmation read transaction. A lapsed scenario replacement
preview also grants no authority while its still-valid draft remains available for a fresh
preview. Unusable rows stay bounded by the existing expiry cleanup or a later preview/draft
write to the same session-scoped key.
A stale, cancelled, expired, or replaced structured decision is a protected-confirmation
conflict (`409` internally), including a cancellation whose exact identity was valid when
read but expired or changed before the compare-and-delete. If detected after SSE admission,
the stream reports a localized error telling the user to refresh and review the current
preview, then persists/sends
`FAILED`; it does not report a generic system failure, invoke the model or a platform tool,
or consume the newer pending preview.
Natural-language classification remains available only for
non-destructive choice prompts and cannot create or consume protected authority.
The token is valid for 15 minutes and is consumed once
before mutation; a second tool call in the same model response cannot reuse it. Wrong,
expired, cross-session, cross-user, changed-preview, and replayed tokens return a no-write
`409` with `requiresUserConfirmation=true` and a fresh preview where available. Explicit
cancellation clears the relevant pending action; session/account deletion and expiration
also clear it. Pending confirmations are stored in the shared database and consumed with
an optimistic compare-and-delete, so restart or instance switching preserves both the
binding and single-use guarantee. This binding applies uniformly to device, template, rule,
specification, verification-trace, simulation-trace, task/run deletion, formal-fix
application, and Board/edit-history clear.

`RESULT_UNAVAILABLE` is distinct from both success and failure. It means response details
could not be safely serialized, bounded, persisted, or returned after the tool reached its
response stage, or that a mutation-capable tool threw or returned an unusable structural result
after execution started and its write outcome therefore cannot be proved. If
`mutationMayHaveCommitted=true`, affected data is refreshed, the result is counted as
unconfirmed rather than usable, the loop stops so it cannot act on stale assumptions, and
the user is told to inspect current state before retrying. With
`mutationMayHaveCommitted=false`, no mutation refresh is sent; independent later calls and
planning may continue, while the unavailable step remains explicit and prevents the turn
from being presented as completely successful.

**Request body**: `ChatRequestDto`

| Field | Type | Rules |
| :--- | :--- | :--- |
| `sessionId` | `String` | Required; ≤64 characters |
| `content` | `String` | Required; ≤10000 characters and must contain at least one non-Unicode-whitespace code point |
| `turnId` | `String` | Required non-blank value, ≤64 characters. The client generates a unique value used to associate the user message and terminal assistant record; omission is rejected with HTTP `400`. |
| `locale` | `String` | Optional BCP 47 tag (`zh-CN` / `en`), ≤35 characters, naming the UI language the turn is read in. It selects the language of backend-authored status prose (execution notices, error and interruption messages). When absent, the backend falls back to inspecting `content` for Han characters — a guess that reports English for any message carrying none, so a client with a language setting should always send it. |
| `confirmation` | `ChatConfirmationCommandDto` | Optional explicit protected-action decision: `{ action: "CONFIRM" | "CANCEL", kind: "DESTRUCTIVE" | "DEFAULT_TEMPLATE_RESET" | "SCENE_REPLACEMENT", confirmationId: "opaque identity from the pending action" }`. `confirmationId` is required and non-blank. It is accepted only when that exact kind and identity are currently pending for the session. |

An unknown session, a reused `turnId`, and other admission failures remain synchronous JSON
errors with their documented HTTP status. If the chat thread pool is saturated, the request
is rejected with `503` (`ServiceUnavailableException`) only after its admitted row and lease
have been removed. Provider and processing failures after dispatch are reported as an SSE
frame with an `error` field. The backend first persists the visible `FAILED` or
`PARTIAL` terminal assistant row, sends the error frame, then sends its terminal
acknowledgement as the final data frame. If a provider failure interrupts an answer after visible
text has been sent, the terminal row retains that text followed by the error notice and has
status `PARTIAL`; reloading history
must not replace the partial answer with only an error message. A browser-side transport failure
does not cancel the worker: it still persists the provider/processing outcome as `FAILED` or
`PARTIAL` and the client discovers it through the active-session poll and authoritative history. A durable
ownership loss may persist `DISCONNECTED`; the client reloads authoritative history after the
backend reports the session idle, so that record remains visible even when its SSE frame could
not arrive.
If the terminal row itself cannot be saved, the `error` frame explicitly says history is
incomplete and asks the client to reconcile current history and Board state; no second
terminal insert is attempted and no terminal acknowledgement is sent. Admission rollback
whose outcome cannot be confirmed follows the same no-terminal reconciliation rule.

### Stream frames

Each SSE `data:` frame carries a JSON-serialized `StreamResponseDto`:

```json
{ "content": "partial assistant text" }
```

| Field | Type | Meaning |
| :--- | :--- | :--- |
| `content` | `String` | A chunk of assistant text (streamed incrementally) |
| `error` | `String` | A non-blank server error message. It is structurally distinct from model-authored `content` |
| `command` | `CommandDto` | Optional front-end refresh command: `{ type: "REFRESH_DATA", payload: { target, assistantAction?, assistantSummary? } }`, where `target` is `device_list`, `environment_list`, `rule_list`, `spec_list`, `template_list`, `run_history`, or `board_state`. `assistantAction` is present only for a confirmed meaningful assistant action or an uncertain outcome that must be reconciled. A non-blank `assistantSummary` of at most 240 characters carries the backend's exact localized action summary when one is available. |
| `progress` | `ProgressDto` | Optional live status `{ stage, toolName?, round?, outcome?, successfulSteps?, failedSteps?, unconfirmedSteps?, detail?, claimReview? }`; `detail` is a bounded task-resumption summary, model-authored reasoning summary, operation-aware tool-result summary, or localized reply-check reason |
| `terminal` | `TerminalDto` | Persistence acknowledgement `{ turnId, executionStatus }` for the final assistant row. It must match the request `turnId`, use a documented execution status, occur exactly once, and be the final data frame |

Progress stages and outcomes:

| Stage | Meaning | Outcome when present |
| :--- | :--- | :--- |
| `CONTEXT_READY` | Request accepted; conversation and Board context are available | — |
| `TASK_RESUMED` | A confirmed step is resuming the stored original objective; `detail` contains its bounded user-authored summary | — |
| `PLANNING` | The model is choosing the next tool step for `round` | — |
| `REASONING` | Before tool execution, `detail` carries the model's bounded, sanitized public explanation of current evidence or the remaining question. Complete analysis, relevant alternatives, findings, and limitations follow the required tool results. **Line structure is preserved** and the budget is larger than a tool status line; a long pre-tool plan is not required | — |
| `TOOL_EXECUTION` | `toolName` has started | — |
| `TOOL_RESULT` | The tool returned and cumulative counters were updated | **Required:** `USABLE`, `PARTIAL`, `FAILED`, `RESULT_UNAVAILABLE`, or `CONFIRMATION_REQUIRED` |
| `EXECUTION_GUARD` | Duplicate no-progress execution or the emergency runaway ceiling stopped further calls | **Required:** `NO_PROGRESS` or `EMERGENCY_LIMIT` |
| `CLAIM_REVIEW` | A public sentence was withheld because its current-turn evidence check did not pass; `claimReview` records the precise input and reason, without incrementing tool counters | **Required:** `REPLACED` (confirmed results replace the sentence) or `HIDDEN` (no replacement evidence) |
| `WRITING_RESPONSE` | Public assistant text is being streamed; further tool work may follow | Optional round |

Absent completed/saved run records, including empty history-query results, and explicit completion of analysis, review, or
explanation are descriptions of evidence or assistant work, not claims of a new platform
operation. The Chinese reply check also recognizes explicit phrases declining to claim
verification completion or stating that completion has not been proved, confirmed, or substantiated,
warnings that configuring requirements does not mean verification
has run, causal warnings that saved requirements alone do not establish safe behavior,
references to a previously verified repair candidate, and explicit statements that a repair
analysis or search has finished. Denying that evidence implies a candidate was applied is
not an application claim. A reference
such as "that verified repair's application preview" does not claim that the repair was applied.
Only the local completion marker is exempt; first-person
save claims and adjacent operations still require receipts. These exclusions apply only to the
covered phrase: mixed sentences still check actual operations before or after it independently.
Saying that an analysis is complete does not authorize or prove a rule edit or formal verification.
Coordinated operations sharing that completed subject and actual operations after Markdown
line breaks still require their own receipts.
Visible replacement notices state the specific missing-evidence reason before listing any
confirmed results. The execution details retain the original for inspection.

`CLAIM_REVIEW.claimReview` contains exactly `reasonCode`, `originalText`, and
`matchedText`. The original public sentence segment is retained verbatim, including whitespace;
the matched text is an exact substring. Reasons are `MISSING_TOOL_EVIDENCE` (no usable tool
result), `MISSING_BOARD_SNAPSHOT` (no successful current Board read), and
`MISSING_OPERATION_RECEIPT` (no confirmed platform operation in this turn). A diagnostic
records what the check decided; it does not prove that the language classifier was correct.
Malformed diagnostics, unknown reasons, or diagnostics on another stage are rejected.
The same record is streamed and saved in the owning assistant message's execution trace.
Its original text is shown as a collapsed, escaped quotation under Reply check, separate
from the answer. It is not private provider reasoning, a tool result, or an action receipt,
and is excluded from server diagnostic strings. A `HIDDEN` review cannot support a
`COMPLETED` history state.

Frames are emitted with `MediaType.APPLICATION_JSON`. Notes on framing:

- Text chunks arrive as `StreamResponseDto` objects with a `content` field.
- Front-end refresh commands arrive as separate frames carrying `command`. They are collected
  from usable tool results that carry the tool's authoritative changed/accepted marker and from
  result-unavailable tools that explicitly say a mutation may already have committed,
  then sent before the final streamed assistant text. If a later planning or reply step
  throws, pending refresh commands are sent before the SSE error when the connection is
  still usable. The persisted partial message records whether that delivery actually completed;
  a failed or partial command send tells the user to reload and inspect current state instead of
  claiming that the client was refreshed.
- Refresh commands without an action receipt are sent first. Receipt-bearing commands are
  sent last, so multi-collection effects such as device/environment reconciliation complete
  before the client announces the assistant action. A successfully delivered command is
  settled once: a later final-response failure records the completed action and refresh in the
  persisted partial assistant message without emitting the same command again.
- `assistantAction` is one of `DEVICE_ADDED`, `DEVICE_DELETED`, `DEVICE_UPDATED`,
  `RULES_UPDATED`, `REPAIR_APPLIED`, `SPECIFICATIONS_UPDATED`, `ENVIRONMENT_UPDATED`,
  `SCENE_APPLIED`, `DEFAULT_TEMPLATES_RESET`, `TEMPLATES_UPDATED`,
  `FORMAL_VERIFICATION_RUN`, `VERIFICATION_TASK_STARTED`, `SIMULATION_TASK_STARTED`,
  `EXPLORATION_TASK_STARTED`, `RUN_HISTORY_UPDATED`, `BOARD_UNDONE`, `BOARD_REDONE`,
  `BOARD_CLEARED`, or `OUTCOME_RECONCILED`.
  Preview, list, rejected, unchanged, and unaccepted-cancellation results emit no action receipt.
  `OUTCOME_RECONCILED` means a mutation may have committed but the tool could not return a
  confirmed result; it must not be rendered as success.
- Tool-aware inference streams public assistant text directly through the visible reply's evidence
  guards, including brief explanations before tool calls. Hidden provider reasoning and tool arguments
  are never sent as visible content. The adapter assembles text and
  interleaved tool arguments with the SDK accumulator and waits for a completed choice or
  Responses `response.completed` event before
  returning calls to the executor. Missing finish markers, truncated or filtered responses, and
  stream errors fail explicitly; partial arguments never authorize tool execution. When no tools remain,
  the same inference response supplies the complete answer: a normal turn does not issue another model
  request to regenerate it. Confirmation boundaries, runaway guards, unavailable results and a model
  returning no text can still require a separate tool-free summary. That summary requires a normal `stop`
  finish marker or completed Responses event; EOF alone, filtering, truncation, or unexpected tool calls are not completion.
  Responses calls use stateless requests. The original completed reasoning, message, and function-call
  items travel together with their matching tool results inside the current turn. Opaque encrypted
  reasoning is excluded from API serialization, diagnostic strings, and persisted chat history.
  Leading system messages become explicit Responses instructions, preventing a compatible relay
  from substituting an unrelated default agent prompt. Later system reminders retain their position
  beside the current tool evidence; no project instructions or available actions are removed.
  Safe reasoning summaries remain separate from visible answer text. Protocol and reasoning selection
  are owned by [LLM configuration](../getting-started/configuration.md#llm-ai).
  Healthy completed requests can reuse their connection across tool rounds. Cancelling an
  unfinished response closes it; its partial content cannot complete a later request.
  Stop cancels the owning network call before closing the SDK stream, so a blocked reader
  cannot delay cancellation or affect another user's request in the shared connection pool.
  Request-scoped SDK clients borrow the provider's shared transport. Their cleanup cannot
  shut down its dispatcher or pool; the provider owns shutdown of those shared resources.
  Planning and tool-free replies both attach cancellation before response headers arrive.
  They share the same streaming adapter, preserving normal-finish checks and rejecting unfinished
  tool arguments. Connection setup and SDK retry policy are owned by
  [LLM configuration](../getting-started/configuration.md#llm-ai).
  The prompt asks for an immediate short public sentence, then tool work; complete analysis
  belongs with the resulting evidence rather than a repeated pre-tool plan. Provider diagnostics
  record connection protocol, reuse, setup time, first chunk, first public text, and total request
  duration without recording prompts or upstream exception messages.
  Tool-aware answers and separate summaries share the same presentation rules: requested
  coverage stays complete, while each finding and shared limitation is explained once.
  Repeated identical evidence replacements produce one notice; every unsupported segment
  is still withheld. Statements that no completed/saved formal record exists describe
  available evidence rather than claiming a new operation.
  Replies address each explicitly named device, distinguish observed rules from suggested links,
  and explain the evidence behind inspection priorities. They compare complete guards before calling
  opposite actions a simultaneous conflict, and avoid repeating chains and priorities across sections.
  Shared chain segments are grouped together with their conditions, gaps and inspection priorities;
  follow-ups develop the requested distinction instead of retelling the topology.
  Multi-device topology prompts favor one compact chain table and one inspection list. A cited
  specification keeps its actual IF/THEN, rather than substituting a downstream automation state.
  Later tool rounds explain only new evidence or the remaining question instead of repeating scope
  and counts. Full formal cross-checks start with the outcome and use one rule/chain inventory,
  one specification/result inventory, indexed counterexample tables and one prioritized list.
  Shared-variable dependencies stay beside the related chain or check; an explicit variable inventory
  is included when requested. Requested items and complete conditions remain present without prose
  retellings of those inventories or another concluding checklist;
  causal claims need the complete guard and relevant transition in that same trace. An initial-state
  prohibition failure precedes automation. Complete generation with no omissions is distinct from
  conservative abstractions, missing desired properties and actual violations.
  Automation rules, configured specifications and verified properties remain distinct;
  specification coverage must match the property's actual conditions and target. Applicable-specification
  lists contain only tool-returned specifications; related automation rules are labelled separately,
  and proposed properties are explicitly not yet configured.
  After tool results, one current-evidence reminder is moved to the end of the model context, rather
  than accumulated every round. It requires fresh evidence to override earlier assistant prose and
  treats values first changing at the same state index as jointly observed, with no invented order.
- A typed transient provider stream interruption permits one continuation request per user turn. It
  preserves already emitted, evidence-checked text and completed tool results, discards unfinished text
  and tool arguments, and asks the model to finish the remaining objective without repeating prior text
  or completed actions. The continuation still receives the tool catalog and must return fully validated
  calls before any new execution. A `PLANNING` event explains that the connection was interrupted and
  the reply is resuming. Quota/configuration errors and malformed or abnormally finished responses are
  not treated as recoverable interruptions. A second interruption remains an explicit error and partial
  terminal record; an explicit Stop prevents continuation.
  Upstream HTTP `502`, `503`, and `504` also enter this single continuation path, including before
  response text arrives. They do not trigger SDK retries or a protocol change; other upstream
  rejection statuses retain explicit failure semantics.
  An actual HTTP/2 stream reset marks only that turn's later model calls for the alternative
  HTTP/1 transport, retaining the same model, timeout, cancellation, and single-continuation
  rules. Ordinary I/O, quota, parsing, and configuration failures do not switch protocols.
  Schema discovery follows the [AI tool catalog contract](ai-tools.md). Attempted actions
  retain their complete schemas, including after partial or failed results, so correcting
  arguments does not require another preparation request. Only a validated preparation
  result can add newly selected action schemas; confirmation and outcome guards still apply.
  Existing-rule edits use the [rule tool contract](ai-tools.md#rules): a current baseline
  is checked at the atomic write boundary, the rule keeps its identity and order, and the
  result remains explicitly unverified until a new formal check completes. The same rule
  refresh and mutation receipt path presents the edit without substituting a deletion preview.
  Ordinary Board writes also follow the [pre-dispatch authorization contract](ai-tools.md):
  asking for analysis or a repair search does not authorize temporary saved-rule edits.
- While a local turn is active, a compact status line stays in the fixed composer beside Stop, even with
  execution details collapsed or the answer scrolled. The inactive draft field yields its space during
  streaming. It shows the current activity and elapsed time. After 15 seconds without text or progress,
  it explains that the next update is pending and the user may stop; it does not invent a completion
  percentage or claim the upstream model is progressing. A new update clears that wait hint.
- A `recommend_scenario` result whose deterministic `objectiveStatus` is `PARTIAL` remains
  reviewable, but the final assistant row is `PARTIAL` and carries a server notice that
  missing core scene parts were not completed.
- Tool results are accepted only as non-empty JSON objects. Control fields such as
  `requiresUserConfirmation`, `resultAvailable`, `resultStatus`, `objectiveStatus`,
  `mutationMayHaveCommitted`, `errorCode`, and `status` must use their documented scalar
  types and values. An empty object or malformed control field is a failed tool result, not
  a successful step. `resultStatus` and `resultAvailable` must be present together:
  `SUCCESS`/`PREVIEW` pair with `true`, while `RESULT_UNAVAILABLE` pairs with `false`.
  A mutation-capable tool must also return its documented authoritative marker (such as
  `operation`, `deleted`, `taskAccepted` plus `taskId`, or `outcome`) before the result is usable;
  a message-only JSON body is treated as an unconfirmed result and cannot authorize a later
  dependent tool call.
- A usable result from a mutation-capable tool is not automatically mutation evidence. Read/list
  actions, previews, semantic no-ops, and rejected or unaccepted operations emit no action receipt;
  the final-response guard therefore cannot use them to support a claim that platform state changed.
- Saved counterexample lists and repair analyses also supply authoritative progress details. Counts
  are accepted only from consistent, typed result fields. These details identify saved-snapshot
  records and unapplied suggestions; an empty trace history is not a passed verification. An
  unsupported statement can therefore be replaced with the information actually read while later
  repair work continues, instead of stopping solely because a successful read had no display detail.
  Proposed numeric thresholds remain in the compact repair detail. Replacement replies keep
  the most recent authoritative details, so earlier reads do not crowd out the final suggestion.
- A valid `CONFIRMATION_REQUIRED` preview can likewise restore its actual no-write details
  when an unsupported reply is replaced. It still supplies neither a mutation receipt nor
  completed-operation evidence, and the turn remains awaiting the user's explicit confirmation.
- Progress frames arrive before and between potentially slow model/tool calls. They let the UI
  show a full-width ReAct-style record of sanitized reasoning summaries, localized actions,
  observations, confirmation points, cumulative outcomes, and elapsed time. `REASONING` is
  audit-oriented reasoning requested from the model, not the provider's private hidden chain-of-thought.
  It is asked to *work the problem out* — decompose, cite observed state, name a rejected alternative
  when the call is a judgement, and verify its own result — rather than narrate the steps it is about
  to take; a round that returns nothing is reported as such and never replaced with canned wording
  implying reasoning happened. Sanitization removes confirmation tokens and generated identifiers
  while leaving ordinary prose intact: the identifier pattern requires a digit, so a compound
  adjective like "rule-based" is no longer redacted mid-sentence, and over-long reasoning is cut at a
  sentence or line boundary rather than mid-clause.
  Compatible-provider fields explicitly named as safe summaries (`reasoning_summary`,
  `reasoningSummary`, `reasoning_summary_content`, `analysis_summary`, or
  `analysisSummary`) are accepted through the provider adapter. Raw `reasoning_content`
  and `analysis` fields are deliberately ignored.
  Live frames are not stored as separate rows. After completion, the exact emitted event list
  and elapsed time are serialized on the final assistant message, so reloads preserve task
  resumption, confirmation, and execution-guard boundaries. Missing or malformed persisted
  execution evidence is exposed as unavailable; history loading does not reconstruct a
  different user-visible trace from internal tool rows.
- Every planning round receives the complete registered tool catalog. The model can use
  conversation context and tool schemas to select zero or more tools across domains;
  pending-message semantics are classified by the configured model, while the actual
  authorization remains server-scoped to an existing pending kind, exact target/digest,
  authenticated user/session, 15-minute lifetime, and single-use token.
- Board refresh targets are `device_list`, `environment_list`, `rule_list`, `spec_list`,
  `template_list`, and `run_history`. A tool emits every target it may have changed;
  device mutations therefore also refresh the shared Environment Pool, while async task
  creation/cancellation, sync verification, and saved-trace deletion refresh run history.
- A persisted processing error is emitted as a structured `error` frame,
  followed by its terminal acknowledgement. Admission-outcome and terminal-persistence
  errors close without a terminal frame and require authoritative reconciliation.
- Model-authored `content` is never classified by a text prefix. Literal text beginning with
  `[ERROR]` remains ordinary assistant content.
- The frontend validates structured `command`, `progress`, and `terminal` objects before invoking any
  UI callback. Unknown stages, outcomes, negative counters, or malformed payloads terminate
  the stream as an invalid frame; they are never treated as ordinary assistant text.
- The client accepts only a JSON object with exactly one non-null, valid `content`, `error`,
  `command`, `progress`, or `terminal` payload. Empty objects, arrays, raw text, unknown or null fields,
  multiple semantic payloads, invalid field types, and stage/outcome mismatches terminate the
  stream as `INVALID_FRAME`; they are never treated as assistant content or normal completion.
- A stream is complete only after a valid terminal frame. Clean EOF after content, command,
  or progress frames is `INCOMPLETE_STREAM`, not successful completion. The backend emits
  `terminal` only after the matching assistant row has been persisted. A transport reset after
  a valid terminal frame does not revoke that persisted outcome.
- The frontend transport uses the caller's current request ownership before reporting an
  error. Stop detaches that request before registering the durable stop and aborts it only
  after the stop response. If SSE ends first, the old transport still rejects its typed
  incomplete-stream error, but the detached view receives no duplicate error notification.
  Stop settlement remains responsible for checking activity and reloading authoritative
  history; an unexpected EOF on the current request still reports `INCOMPLETE_STREAM`.

### Consuming the stream (frontend)

The frontend does **not** use axios for this endpoint — it uses the native `fetch`
API and reads `response.body.getReader()`, so the `Authorization: Bearer <token>`
header is set manually. See
[../guides/frontend-integration.md](../guides/frontend-integration.md) for the
`sendStreamChat(...)` wrapper (with `onMessage` / `onCommand` / `onError` / `onFinish`
callbacks and `AbortController` support). `onFinish(terminal)` means the matching terminal
assistant row was acknowledged, not merely that the reader reached EOF. For a persisted
server error, `onFinish` receives that acknowledgement before `onError` receives the
`SERVER_FRAME` error. A client abort does not masquerade as completion.

`REFRESH_DATA` commands use a promise-returning component callback rather than a
fire-and-forget event. The assistant remains interaction-locked until the owning Board
method confirms the targeted refresh. If that refresh fails, the client immediately
attempts the client-only `board_state` reconciliation. A second failure leaves a visible,
localized retry panel open and keeps assistant requests, scene replacement, and trace
playback locked until a later full reconciliation succeeds.
When `assistantAction` is present, the frontend shows `assistantSummary` when supplied and
otherwise uses the localized action label. The receipt appears only after the targeted refresh
or fallback reconciliation succeeds. This ordering prevents the assistant from claiming a
visible change before the UI has read authoritative state.

The Stop control first sends `POST /api/chat/sessions/{sessionId}/stop` with the local
turn id and waits for that durable stop fence before aborting the browser stream or polling
activity. A reattached response whose turn id is unavailable sends `turnId: null` and stops
the current session execution. This distinguishes an explicit user stop from an unexpected
transport loss and prevents a quick Stop from missing a request that has not entered stream
admission yet. Concurrent quick Stops retain independent pre-admission turn fences instead of
overwriting one another; those bounded fence rows are removed with their owning session through
the same database-level cascade used by the rest of chat history. Each fence is timestamped
with the database clock, expires after two minutes, and is purged before admission and when
another Stop is recorded; at most 64 live turn fences are retained per session. An expired
fence therefore cannot cancel a later request that happens to reuse an old turn id. The owning
backend immediately closes a blocked provider stream or cancels a pending
planning future; another backend instance observes the durable stop flag during lease
maintenance and closes its local work handle. A synchronous `fix_violation` analysis binds
that handle to its read-only worker, so Stop interrupts the search and releases its active
native solver through the existing cancellation path. Its AI result reports explicit cancellation
instead of presenting the interrupted search as a timeout or an unsuccessful completed search.
Ending that scope disarms the callback
before the thread can be reused. Waiting on an independent async task also stops promptly;
the task itself requires its separate cancellation command. Stop cannot roll back a mutation
transaction already running on the server. A tool that has already returned is still classified and persisted before the
worker stops when the same execution still owns the lease, so committed writes and
confirmation previews do not lose their audit result. A worker replaced by a newer execution
cannot persist that result or a terminal assistant row. The terminal-message transaction locks
the session and rechecks both durable stop flags; an explicit stop committed first is persisted
as `STOPPED` even if the browser abort reaches the worker first or the worker had already
computed `COMPLETED`, `PARTIAL`, or `FAILED`. If the transport is still writable when a worker
observes a cross-instance stop, it sends that persisted `STOPPED` terminal frame and completes
the emitter; an already-broken transport is still completed server-side instead of remaining
allocated until the SSE timeout.
An explicit Stop still polls the session activity endpoint until `active=false`, keeps assistant
mutations locked during that settling period, and only then reloads message history, board
collections, and run history. Switching sessions or creating a new conversation is different:
the browser detaches the old SSE transport without calling Stop. The server keeps the execution,
tool writes, terminal status, and execution trace under the old session, while the sidebar keeps
that row marked as active. The user can continue in another conversation; the selected
conversation alone locks its composer. Any active conversation still protects full-scene
replacement, full-scene clear, and historical playback globally because those operations share
one Board; ordinary targeted edits retain their existing server-serialized semantics.
For New Chat, the client creates the destination session before detaching the current transport;
an HTTP failure or incomplete create response therefore leaves the original live execution visible
and attached instead of stranding the user between conversations. If the user explicitly selects
another row while creation is in flight, that newer selection wins and the created row remains
available in the sidebar rather than taking focus later.
The client detects active-to-idle background rows through both polling and foreground session-list
refresh, then reconciles the authoritative Board and shows a completion notice. Opening that session later loads its persisted
terminal row and trace; live SSE progress is not replayed. Once explicit-stop or reattached remote
work is confirmed idle, authoritative history may legitimately contain only its user turn when
admission rollback or terminal persistence did not complete; the client replaces optimistic
state, restores a draft only when that user turn is absent, and unlocks instead of waiting forever
for a nonexistent terminal row.
The assistant entry renders running and unread counts independently, so unread results do not hide
other conversations that are still executing. Active session rows keep their Stop control but
disable Delete until the server reports them idle.
Before sign-out, the client refreshes the authoritative session list, stops every active row,
waits for settlement, and reconciles the Board. The application shell performs the same sequence
when the lazy chat panel was never mounted. As a final server-side backstop, `/api/auth/logout`
marks all currently active chat leases explicitly stopped before revoking the token. Closing only
a panel, tab, or browser remains different and does not request Stop.
The preparation sequence pins its initiating credential and view/account scope before its first
await. An account change, credential replacement, or view teardown abandons the remaining steps;
an old preparation cannot stop the replacement account's sessions or clear its reconciliation state.
Message content is ordinary text in history, streaming, rendering, and copying. Execution progress
uses structured frames; the client does not strip former prose prefixes or `CallEnd|>` markers.
Terminal-confirmed completion also waits for `active=false` and reloads
authoritative message history so persisted terminal status wins over locally inferred
progress. That normal path replaces the local response only when the terminal row carries
the same `turnId`; an older terminal reply cannot erase the current request. An accepted
protocol or transport failure instead replaces optimistic state with the complete
authoritative history after idle settlement, even when the valid result is user-only. Closing the floating
panel only hides it and does not abort the request. If three consecutive activity checks
fail, each check uses a dedicated 2.5-second timeout instead of the general 100-second
REST timeout, so the client reaches an outcome-unknown warning and authoritative
reconciliation within seconds rather than several minutes. It does not claim cancellation
or automatically repeat the command. The
client-only `board_state` refresh target is used for full reconciliation; it is not an AI
tool result.
Frontend cross-tab discovery, immediate locking, foreground refresh, and settlement behavior
are owned by the [frontend integration guide](../guides/frontend-integration.md).
Live SSE progress frames are not replayed to a new connection; the complete persisted
execution trace becomes available with the terminal assistant row.
If the activity endpoint remains reachable but still reports `active=true` for the
10-second settlement window, the client stops spinning, keeps the interaction lock, and
asks the user to retry settlement later; it does not treat a running tool as cancelled.

Signing out asks the mounted assistant to perform the same settlement first. A confirmed
idle/reconciled result proceeds normally; an outcome-unknown result requires an explicit
second confirmation, and a failed authoritative reconciliation blocks sign-out until the
user retries synchronization. An SSE `401` clears local authentication and navigates to
the login route like the axios interceptor. An SSE `403` is shown as an authorization
failure and does not log out an otherwise valid session.
Every stream and session-history load has a client request epoch. Late chunks, commands,
completion callbacks, or history responses from a stopped/replaced request are ignored,
so they cannot clear or overwrite a newer conversation. Session-list polling is fenced by the
same list version used by create, delete, and foreground refresh; an older poll cannot remove a
newly created row, restore a deleted row, or duplicate a row that it observed before the create
response arrived. Polling is deferred while a foreground list refresh is in flight. Loading session history has a
separate UI state and does not expose a non-functional "stop response" control.

The floating assistant is mounted lazily on its first open and then hidden rather than
destroyed when the user closes the panel. Closing the panel therefore preserves the
selected conversation and does not mean "stop receiving"; the explicit stop control is
the operation that aborts the browser stream and triggers reconciliation. On the first
mounted open, the client requests the session list even when the panel was already
visible before the component finished loading. The sidebar distinguishes loading, empty,
and failed states and offers an explicit retry after a failed list request. Before the
first response chunk arrives, the assistant's pending status is rendered inside one
compact assistant bubble rather than as an empty message followed by detached status
text.
The client treats any successful HTTP response as crossing the synchronous rejection
boundary. This is not proof of dispatch or persistence: a `2xx` stream can carry the
admission-outcome-unknown warning when cleanup could not be confirmed. A missing or unreadable
response body remains a localized stream error, but the client does not remove a turn whose
admission outcome may be unknown. It waits for idle settlement and reloads authoritative
history and Board state. The same rule applies when a dispatched request loses transport before
any HTTP response arrives, because the server may already have admitted it. Only an explicit
pre-stream `400`, `409`, `429`, or `503` proves rejection: the client then removes optimistic
user and assistant placeholders, restores an ordinary text draft, and leaves protected-action
confirmation state intact. A proven rejected request therefore never appears as persisted
history. If authoritative history later proves that an ambiguous ordinary-text turn was not
admitted, the client restores its draft at that point.

Reloading authoritative conversation history is part of settlement, not a best-effort display
refresh. A failed reload or a terminal-confirmed turn whose matching terminal row is absent keeps
assistant mutations locked and exposes the same reconciliation retry action; the client unlocks
only after history replacement succeeds.

Backend-supplied safety notices and fallback explanations follow the language of the
current user message for Chinese and English conversations. This applies to no-write
confirmation previews, failed or result-unavailable tool steps, execution-guard and
missing-reply fallbacks, and mapped stream errors. These deterministic notices remain
visible and are persisted with the assistant reply; raw English control text is not
prepended to an otherwise Chinese answer.
Provider-unavailable notices recommend retrying and inspecting service logs if the failure persists;
they do not infer an invalid endpoint from a transport or provider failure. The provider adapter logs
HTTP status when available and a bounded chain of exception class names, without upstream exception
messages, request headers, or response bodies.
An unrecovered typed connection interruption instead states that the turn did not finish and asks
the user to review completed steps before continuing. It remains an error/partial record, not success.

Client-detected stream protocol failures use stable error kinds (`HTTP_ERROR`,
`MISSING_BODY`, `INCOMPLETE_STREAM`, `INVALID_FRAME`, `SERVER_FRAME`) and are localized by
the frontend. Internal parser messages such as `No response body` remain diagnostic
details and are not displayed verbatim in a localized conversation.

> Note: this endpoint derives its base URL from `VITE_API_BASE_URL` (empty by default →
> a relative `/api`, proxied), the same source the axios layer uses.
