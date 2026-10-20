# REST Endpoint Index

**This document is an index only.** It lists every backend endpoint with its HTTP
method, path, owning controller, and a one-line note. It intentionally contains **no
DTO fields, request/response bodies, or examples** — those live in the domain API
documents linked from the "Detail" column.

> **Single source of truth**: this is the only document that lists the full endpoint
> set. It is meant to be **regenerated (semi-automatically) from controller
> annotations** rather than hand-edited — see [Regeneration](#regeneration) below.
> Do not add field-level detail here; add it to the domain doc.

All paths are relative to the server root. Response representations and authentication
follow [overview.md](overview.md). SSE streams and successful SMV downloads are direct
responses rather than JSON envelopes.

Verified against code on 2026-10-09.

---

## Auth — `AuthController`

| Method | Path | Note | Detail |
| :--- | :--- | :--- | :--- |
| POST | `/api/auth/register` | Register a new user | [auth.md](auth.md) |
| POST | `/api/auth/login` | Log in, returns JWT token | [auth.md](auth.md) |
| POST | `/api/auth/logout` | Log out, blacklists token | [auth.md](auth.md) |
| GET | `/api/auth/account` | Read the current account profile | [auth.md](auth.md) |
| PATCH | `/api/auth/account/username` | Change the current account username | [auth.md](auth.md) |
| POST | `/api/auth/account/password` | Change password and invalidate old login tokens | [auth.md](auth.md) |
| DELETE | `/api/auth/account` | Permanently delete the current account and user-owned data | [auth.md](auth.md) |

## Board storage — `BoardStorageController`

| Method | Path | Note | Detail |
| :--- | :--- | :--- | :--- |
| GET | `/api/board/model-fingerprints` | Read current verification/exploration and simulation model identities | [board.md](board.md#current-model-fingerprints) |
| GET | `/api/board/nodes` | List device instances | [board.md](board.md) |
| POST | `/api/board/nodes` | Atomically append one or more device instances | [board.md](board.md) |
| PUT | `/api/board/nodes/layout?nodeId={nodeId}` | Replace only one device's complete canvas layout | [board.md](board.md) |
| PUT | `/api/board/nodes/runtime?nodeId={nodeId}` | Replace only one device's complete device-local runtime configuration | [board.md](board.md) |
| PATCH | `/api/board/nodes/label?nodeId={nodeId}` | Rename one device instance and refresh spec display labels | [board.md](board.md) |
| GET | `/api/board/nodes/deletion-preview?nodeId={nodeId}` | Preview every rule, specification, and Environment Pool effect of deleting one device | [board.md](board.md) |
| POST | `/api/board/nodes/delete?nodeId={nodeId}` | Confirmed atomic device/rule/spec cascade delete | [board.md](board.md) |
| GET | `/api/board/environment` | List and self-heal the board environment pool | [board.md](board.md) |
| POST | `/api/board/environment` | Apply itemized compare-and-set field patches to the board environment pool | [board.md](board.md) |
| GET | `/api/board/specs` | List specifications | [board.md](board.md) |
| POST | `/api/board/specs` | Create one specification | [board.md](board.md) |
| DELETE | `/api/board/specs/{specId}` | Delete one specification when its confirmed snapshot is still current | [board.md](board.md) |
| GET | `/api/board/rules` | List automation rules | [board.md](board.md) |
| POST | `/api/board/rules` | Create one automation rule | [board.md](board.md) |
| PUT | `/api/board/rules/order` | Atomically replace rule execution order | [board.md](board.md) |
| DELETE | `/api/board/rules/{ruleId}` | Delete one automation rule when its confirmed snapshot is still current | [board.md](board.md) |
| GET | `/api/board/edits/availability` | Current undo/redo availability from the per-user edit journal | [board.md](board.md) |
| POST | `/api/board/edits/undo` | Reverse the newest reversible Board edit, including device updates, Environment Pool edits, and automatic fixes | [board.md](board.md) |
| POST | `/api/board/edits/redo` | Re-apply the oldest undone board edit | [board.md](board.md) |
| GET | `/api/board/edits/clear-preview` | Preview the exact undo/redo history impact for confirmation | [board.md](board.md) |
| POST | `/api/board/edits/clear` | Token-confirmed discard of undo/redo history without changing Board data | [board.md](board.md) |
| GET | `/api/board/replacement-preview` | Preview authoritative current-scene counts and obtain the confirmation impact token | [board.md](board.md) |
| POST | `/api/board/scene` | Confirmed portable scene-file import; the server maps the file onto the write contract | [board.md](board.md) |
| POST | `/api/board/batch` | Confirmed atomic full-scene replacement/clear with exact template snapshots | [board.md](board.md) |
| GET | `/api/board/snapshot` | Atomic initial Board semantic snapshot | [board.md](board.md) |
| GET | `/api/board/layout` | Get board layout | [board.md](board.md) |
| POST | `/api/board/layout` | Save board layout | [board.md](board.md) |
| GET | `/api/board/templates` | List device templates | [board.md](board.md) |
| GET | `/api/board/templates/schema` | Download the authoritative device-template JSON Schema | [board.md](board.md) |
| POST | `/api/board/templates` | Create custom template | [board.md](board.md) |
| GET | `/api/board/templates/{id}/deletion-preview` | Preview exact template-deletion blockers and obtain an impact token | [board.md](board.md) |
| POST | `/api/board/templates/{id}/delete` | Confirm deletion against the unchanged preview | [board.md](board.md) |
| GET | `/api/board/templates/defaults/reset-preview` | Preview exact default-type reset effects | [board.md](board.md) |
| POST | `/api/board/templates/defaults/reset` | Confirm and atomically apply the previewed default-type reset | [board.md](board.md) |
| POST | `/api/board/rules/recommend` | AI rule recommendations | [board.md](board.md) |
| POST | `/api/board/devices/recommend` | AI device recommendations | [board.md](board.md) |
| POST | `/api/board/rules/check-duplicate` | Deterministic duplicate-rule check used before saving a rule | [board.md](board.md) |
| POST | `/api/board/rules/check-similarity` | Explicit AI rule-similarity check available from `RuleBuilder` | [board.md](board.md) |
| POST | `/api/board/specs/recommend` | AI specification recommendations | [board.md](board.md) |
| POST | `/api/board/scenario/recommend` | AI importable scene-draft recommendation | [board.md](board.md) |
| GET | `/api/board/recommendations/{requestId}` | Read the server-observed stage of an active or just-finished standalone recommendation | [board.md](board.md) |
| DELETE | `/api/board/recommendations/{requestId}` | Cancel an in-flight standalone recommendation | [board.md](board.md) |

## Verification — `VerificationController`

| Method | Path | Note | Detail |
| :--- | :--- | :--- | :--- |
| POST | `/api/verify` | Synchronous verification | [verification.md](verification.md) |
| POST | `/api/verify/async` | Submit async verification; returns the accepted task snapshot for polling | [verification.md](verification.md) |
| GET | `/api/verify/tasks` | Active/failed/cancelled verification task status (completed results excluded) | [verification.md](verification.md) |
| GET | `/api/verify/tasks/{id}` | Task status | [verification.md](verification.md) |
| DELETE | `/api/verify/tasks/{id}` | Dismiss a failed/cancelled task with no result | [verification.md](verification.md) |
| GET | `/api/verify/tasks/{id}/progress` | Task progress (0–100) | [verification.md](verification.md) |
| POST | `/api/verify/tasks/{id}/cancel` | Request cancellation and return the actual outcome | [verification.md](verification.md) |
| GET | `/api/verify/runs` | Completed verification result summaries | [verification.md](verification.md) |
| GET | `/api/verify/runs/{id}` | Completed verification result detail | [verification.md](verification.md) |
| GET | `/api/verify/runs/{id}/traces` | Replayable counterexamples nested under one verification result | [verification.md](verification.md) |
| GET | `/api/verify/runs/{id}/smv` | Download the SMV model this run checked (plain text attachment) | [verification.md](verification.md) |
| DELETE | `/api/verify/runs/{id}` | Delete a verification result and all linked counterexamples | [verification.md](verification.md) |
| GET | `/api/verify/traces` | List verification traces | [verification.md](verification.md) |
| GET | `/api/verify/traces/{id}` | Trace detail | [verification.md](verification.md) |
| DELETE | `/api/verify/traces/{id}` | Delete trace | [verification.md](verification.md) |
| GET | `/api/verify/traces/{id}/fault-rules` | Counterexample rule involvement with source-model completeness and limitations | [verification.md](verification.md) |
| POST | `/api/verify/traces/{id}/fix` | Generate signed fix suggestions | [verification.md](verification.md) |
| GET | `/api/verify/fix-requests/{requestId}` | Read the server-observed stage of an active or just-finished fix search | [verification.md](verification.md) |
| DELETE | `/api/verify/fix-requests/{requestId}` | Cancel an in-flight fix search | [verification.md](verification.md) |
| POST | `/api/verify/traces/{id}/fix/apply` | Apply the exact signed suggestion after snapshot checks | [verification.md](verification.md) |
| GET | `/api/verify/tasks/{id}/traces` | Traces for a specific async verification task | [verification.md](verification.md) |

## Simulation — `SimulationController`

| Method | Path | Note | Detail |
| :--- | :--- | :--- | :--- |
| POST | `/api/simulate` | Synchronous simulation (not persisted) | [verification.md](verification.md) |
| POST | `/api/simulate/async` | Submit async simulation; returns the accepted task snapshot for polling | [verification.md](verification.md) |
| GET | `/api/simulate/tasks` | Active/failed/cancelled simulation task status (completed results excluded) | [verification.md](verification.md) |
| GET | `/api/simulate/tasks/{id}` | Task status | [verification.md](verification.md) |
| DELETE | `/api/simulate/tasks/{id}` | Dismiss a failed/cancelled task with no result | [verification.md](verification.md) |
| GET | `/api/simulate/tasks/{id}/progress` | Task progress (0–100) | [verification.md](verification.md) |
| POST | `/api/simulate/tasks/{id}/cancel` | Request cancellation and return the actual outcome | [verification.md](verification.md) |
| POST | `/api/simulate/traces` | Simulate and persist | [verification.md](verification.md) |
| GET | `/api/simulate/traces` | List saved simulations (summary) | [verification.md](verification.md) |
| GET | `/api/simulate/traces/{id}` | Simulation detail (full states) | [verification.md](verification.md) |
| GET | `/api/simulate/traces/{id}/smv` | Download the SMV model file for this simulation (plain text attachment) | [verification.md](verification.md) |
| DELETE | `/api/simulate/traces/{id}` | Delete simulation | [verification.md](verification.md) |

## Counterexample exploration — `FuzzController`

| Method | Path | Note | Detail |
| :--- | :--- | :--- | :--- |
| GET | `/api/fuzz/model-fingerprint` | Read the current Board's canonical bounded-search model fingerprint without creating a task | [fuzzing.md](fuzzing.md) |
| POST | `/api/fuzz/workload/preview` | Calculate the current Board's authoritative bounded-search workload without creating a task | [fuzzing.md](fuzzing.md) |
| POST | `/api/fuzz/paper-domain/preview` | Read the current Board's authoritative paper-mode initial-state and Event domains without creating a task | [fuzzing.md](fuzzing.md) |
| POST | `/api/fuzz/async` | Capture the current Board and submit a bounded background search | [fuzzing.md](fuzzing.md) |
| GET | `/api/fuzz/tasks` | Active/failed/cancelled exploration task status (completed results excluded) | [fuzzing.md](fuzzing.md) |
| GET | `/api/fuzz/tasks/{id}` | Exploration task detail | [fuzzing.md](fuzzing.md) |
| GET | `/api/fuzz/tasks/{id}/progress` | Exploration progress (0-100) | [fuzzing.md](fuzzing.md) |
| POST | `/api/fuzz/tasks/{id}/cancel` | Request cancellation and return the actual outcome | [fuzzing.md](fuzzing.md) |
| DELETE | `/api/fuzz/tasks/{id}` | Dismiss a failed/cancelled task with no result | [fuzzing.md](fuzzing.md) |
| GET | `/api/fuzz/runs` | Completed exploration result summaries with finding summaries | [fuzzing.md](fuzzing.md) |
| GET | `/api/fuzz/runs/{id}` | Completed exploration result with full findings | [fuzzing.md](fuzzing.md) |
| DELETE | `/api/fuzz/runs/{id}` | Delete an exploration result and all findings | [fuzzing.md](fuzzing.md) |
| GET | `/api/fuzz/runs/{id}/findings` | Full candidate findings for one completed run | [fuzzing.md](fuzzing.md) |
| GET | `/api/fuzz/findings/{id}` | One owned candidate finding with validated frozen replay context | [fuzzing.md](fuzzing.md) |

## Chat — `ChatController`

| Method | Path | Note | Detail |
| :--- | :--- | :--- | :--- |
| GET | `/api/chat/sessions` | List chat sessions | [chat-sse.md](chat-sse.md) |
| POST | `/api/chat/sessions` | Create session | [chat-sse.md](chat-sse.md) |
| GET | `/api/chat/sessions/{sessionId}/messages` | Cursor-paged message history (`beforeId`, `limit`) | [chat-sse.md](chat-sse.md) |
| POST | `/api/chat/sessions/{sessionId}/seen` | Acknowledge the exact terminal assistant message rendered to the user | [chat-sse.md](chat-sse.md) |
| GET | `/api/chat/sessions/{sessionId}/activity` | Check whether server work is still active for the session | [chat-sse.md](chat-sse.md) |
| GET | `/api/chat/sessions/{sessionId}/confirmation` | Read pending protected actions with confirmation identities and targets for explicit UI confirmation | [chat-sse.md](chat-sse.md) |
| POST | `/api/chat/sessions/{sessionId}/stop` | Request an explicit turn-aware stop; body carries the local `turnId` or `null` for reattached work | [chat-sse.md](chat-sse.md) |
| POST | `/api/chat/completions` | Send message — **SSE stream** (not `Result<T>`) | [chat-sse.md](chat-sse.md) |
| DELETE | `/api/chat/sessions/{sessionId}` | Delete session | [chat-sse.md](chat-sse.md) |

---

## Regeneration

This index should be kept honest by generating it from the controllers rather than
editing by hand. The endpoint set is defined by the `@RequestMapping` on each
controller plus the `@GetMapping` / `@PostMapping` / `@DeleteMapping` / `@PatchMapping` on its methods,
under:

```
backend/src/main/java/cn/edu/nju/Iot_Verify/controller/
├── AuthController.java
├── BoardStorageController.java
├── ChatController.java
├── FuzzController.java
├── SimulationController.java
└── VerificationController.java
```

A minimal check (list every mapping) that can seed or validate this table:

```bash
grep -rhnE "@(RequestMapping|GetMapping|PostMapping|DeleteMapping|PutMapping|PatchMapping)" \
  backend/src/main/java/cn/edu/nju/Iot_Verify/controller/
```

**CI suggestion**: fail the build if the set of mappings extracted from the
controllers differs from the paths listed here. That turns "the index drifted from
the code" from a review burden into an automated gate.
