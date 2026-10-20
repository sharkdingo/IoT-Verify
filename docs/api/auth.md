# Authentication API

Field-level contract for `/api/auth`. The `Result<T>` envelope, the `Bearer` scheme,
and error codes are defined once in [overview.md](overview.md); this doc covers the
request/response bodies only.

Verified against code on 2026-10-19. Source: `controller/AuthController.java`,
`service/impl/AuthServiceImpl.java`, `configure/DatabaseSchemaConstraints.java`, `po/UserPo.java`,
`util/JwtUtil.java`, `security/JwtAuthenticationFilter.java`, and `dto/auth/`.

The immutable JWT parser is initialized once per backend instance. Each authenticated request
still verifies the token signature, expiry, and identity claims, then checks token revocation and
the current account credential version; parser reuse does not cache authentication decisions.

---

## `POST /api/auth/register`

Public. Creates a user.

Registration uniqueness conflicts identify the editable field through `data.reasonCode` and
`data.errors`: `PHONE_ALREADY_EXISTS` with `errors.phone`, or `USERNAME_ALREADY_EXISTS` with
`errors.username`. The form localizes these reasons without parsing the English diagnostic message.

**Request body**: `RegisterRequestDto`

| Field | Type | Rules |
| :--- | :--- | :--- |
| `phone` | `String` | Required; must match `^1[3-9]\d{9}$` (mainland China mobile) |
| `username` | `String` | Required; raw input at most 100 characters; NFC-normalized and stripped of surrounding Unicode whitespace; 3–20 Unicode code points afterwards; no control, invisible format, or line-separator characters; must not match the phone-number pattern; unique with case- and accent-sensitive matching; usable for login |
| `password` | `String` | Required; 10–64 characters and at most 72 UTF-8 bytes |

The phone-number pattern reserves identifiers for phone login without consulting
existing accounts. Other accepted usernames use the username namespace; registration
and username changes do not compare them against the phone column's collation.

**Response**: `AuthResponseDto` (under `data`)

| Field | Type |
| :--- | :--- |
| `userId` | `Long` |
| `phone` | `String` |
| `username` | `String` |
| `token` | `String` |

Registration and initial bundled device-type creation are one transaction. The endpoint
returns an authenticated response only when the complete default type catalog was imported.
A missing or invalid bundled definition fails registration and rolls back the new user;
the API does not return a usable-looking account with a partial or empty catalog. Bundled
definitions are parsed and schema-validated once per backend process, then instantiated for
each new user inside the registration transaction. Clients should store `data.token` and enter
the authenticated application directly; a second login request is not required.
Login is rate-limited per normalized account identifier and registration per phone number,
with separate higher source-IP ceilings so ordinary users behind one NAT do not share the
low credential limit. Username buckets retain the normalized identifier's case and accent
because those distinctions identify different valid accounts. Excess attempts return
structured `429` data and `Retry-After`.
The owning limits and multi-instance guidance live in the
[configuration reference](../getting-started/configuration.md#authentication-jwt).
Registration, login, deletion confirmation, and authentication throttling use the same
username normalization, so surrounding whitespace cannot create an unreachable account.

```bash
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"phone":"13800138000","username":"testuser","password":"password123"}'
```

---

## `POST /api/auth/login`

Public. Returns a JWT token.

**Request body**: `LoginRequestDto`

| Field | Type | Rules |
| :--- | :--- | :--- |
| `identifier` | `String` | Required; phone number or username; at most 100 characters; usernames use the registration normalization above |
| `password` | `String` | Required; at most 128 characters |

**Response**: `AuthResponseDto` (under `data`)

| Field | Type | Notes |
| :--- | :--- | :--- |
| `userId` | `Long` | |
| `phone` | `String` | |
| `username` | `String` | |
| `token` | `String` | JWT; send as `Authorization: Bearer <token>` on later requests |

Unknown accounts and incorrect passwords both return `401 Unauthorized` with the same
account-neutral message, so the response works for phone and username login without
revealing whether an identifier exists.

```bash
TOKEN=$(curl -sX POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"identifier":"testuser","password":"password123"}' | jq -r '.data.token')
```

Note the field is `data.token` — the payload lives under the `Result<T>` envelope's
`data`.

Phone-shaped usernames are forbidden, so phone and username identifiers occupy disjoint
namespaces. Login classifies the normalized identifier once and queries only that namespace.

---

## `POST /api/auth/logout`

Authenticated. Requests an explicit stop for every active AI-chat session owned by the
account, then blacklists the current token in Redis (fail-open — see
[overview.md](overview.md)). The stop uses the same durable cross-instance execution flags as
the per-session Stop control. If the durable chat-stop update fails, logout fails before the
token is blacklisted instead of reporting a logout that left unknown chat work running.

- **Request**: no body. Requires `Authorization: Bearer <token>` (the header is read
  directly to identify the token to revoke).
- **Response**: `data` is `null`.
- Logout stops active AI-chat responses and revokes the current token. It does not cancel
  server-side verification/simulation/exploration tasks; those tasks remain associated with the
  account and can be viewed after the user signs in again.

Frontend credential ownership across asynchronous logout is described in the
[frontend integration guide](../guides/frontend-integration.md#auth--apiauthts).

```bash
curl -X POST http://localhost:8080/api/auth/logout \
  -H "Authorization: Bearer $TOKEN"
```

---

## `GET /api/auth/account`

Authenticated. Reads the current account's authoritative profile. The owner comes from
the authenticated user id; the endpoint accepts no other account selector.

**Response**: `AccountProfileDto` (under `data`)

| Field | Type | Notes |
| :--- | :--- | :--- |
| `userId` | `Long` | Stable identity used by all user-owned data |
| `phone` | `String` | Current login phone; read-only in the user center |
| `username` | `String` | Current display name and username login identifier |
| `createdAt` | `LocalDateTime` | Registration time; follows the [timestamp contract](overview.md#timestamp-format) |

The profile contains no password hash, token, or internal authentication state.

## `PATCH /api/auth/account/username`

Authenticated. Changes only the current account's username after verifying its current
password. User id, phone, existing login token, scene, and history ownership stay the same.
The old username stops being a login identifier; phone login remains available.

**Request body**: `UpdateUsernameRequestDto`

| Field | Type | Rules |
| :--- | :--- | :--- |
| `username` | `String` | Required; uses the [registration username rules](#post-apiauthregister) |
| `currentPassword` | `String` | Required; at most 128 characters; must match the current password |
| `expectedUsername` | `String` | Required; at most 100 characters; the authoritative username read before editing |

**Response**: `AccountProfileDto`, as above. A normalized no-op returns the existing
profile without changing account state. The account row is locked before checking the
password and expected username. Another tab's completed rename therefore causes a
conflict instead of being overwritten by an old form. A database unique constraint,
rather than a preliminary availability query, decides concurrent name claims.

## `POST /api/auth/account/password`

Authenticated. Changes the password and invalidates every previously issued login
token for this account. The caller must sign in again; no replacement token is returned.

**Request body**: `ChangePasswordRequestDto`

| Field | Type | Rules |
| :--- | :--- | :--- |
| `currentPassword` | `String` | Required; at most 128 characters; must match the current password |
| `newPassword` | `String` | Required; uses the [registration password rules](#post-apiauthregister); must differ from the current password |

**Response**: void. Password verification, hash replacement, and credential invalidation
commit in one transaction under the account row lock. Concurrent changes using the same
old password cannot both succeed. Invalidation is independent of Redis; its mechanism
belongs to the [authentication scheme](overview.md#authentication).

This command changes credentials only. It does not delete account data or cancel
verification, simulation, or exploration tasks. Requests already authenticated before
the change may finish. The web client's preparation of active AI work and local session
is described in the [frontend integration guide](../guides/frontend-integration.md#auth--apiauthts).

### Account-change rejection and uncertain outcomes

Both mutation endpoints are rate-limited before password work. They use the account-change
buckets documented in the [configuration reference](../getting-started/configuration.md#authentication-jwt).
An incorrect current password returns `400`, retaining the valid session. Missing or
invalid login credentials still follow the normal `401` contract.

Account-change rejections include `data.reasonCode` and `data.errors`, a field-name-to-message
map. Clients localize the stable code rather than matching English diagnostic text.

| HTTP | `reasonCode` | Field | Meaning |
| :--- | :--- | :--- | :--- |
| 400 | `CURRENT_PASSWORD_INCORRECT` | `currentPassword` | Current password did not match |
| 400 | `NEW_PASSWORD_UNCHANGED` | `newPassword` | New password matches the current password |
| 409 | `USERNAME_ALREADY_EXISTS` | `username` | Another account owns the requested username |
| 409 | `ACCOUNT_PROFILE_CHANGED` | `expectedUsername` | The form's username baseline is stale; `data.currentProfile` supplies the current `AccountProfileDto` |

DTO validation retains the existing field-error contract. A definite `4xx` rejection
does not imply that the account mutation succeeded. If a response is missing or a `5xx`
arrives, the mutation may have committed: do not retry automatically. Re-read the profile
after an uncertain rename and ask the user to review the current value. After an
uncertain password change, clear only the initiating local session and ask the user to
sign in to determine which password is current.

---

## `DELETE /api/auth/account`

Authenticated. Permanently deletes the current account and user-owned data.
The database transaction first validates the account and removes all user-owned rows.
Only after that transaction commits does the backend revoke the current token, interrupt
locally executing verification, simulation, counterexample-exploration, and AI-chat work, and
publish cross-instance cancellation fences for active AI recommendation and automatic-fix work. This
prevents a failed deletion from revoking a still-valid session or stopping tasks whose
database state was rolled back. User-owned data deletion removes board devices, rules,
specifications, templates, formal traces,
exploration findings, task rows, and AI chat records. If a
worker wakes after the account is gone, result persistence is rejected by the active-user
lock or by the missing task row, so deleted accounts do not receive late history entries.

**Request body**: `DeleteAccountRequestDto`

| Field | Type | Rules |
| :--- | :--- | :--- |
| `password` | `String` | Required; at most 128 characters and must match the current account password |
| `confirmation` | `String` | Required; at most 100 characters; normalized like login and then must exactly match the current `username` or `phone` |

Password or confirmation mismatches return `400 Bad Request`; they do not revoke the
current session. Missing/invalid authentication still returns `401 Unauthorized`.
Because a missing response or `5xx` can arrive after the deletion transaction committed,
clients must not retry blindly. The web client clears its local session and asks the user
to sign in again to determine whether the account still exists. An explicit `4xx` response
is treated as a definite rejection and leaves the deletion form available for correction
(an authentication `401` still clears the invalid local session through the shared client).

**Side effects**:

- Blacklists the current token after the data-deletion transaction commits, same as logout.
- Interrupts locally executing verification, simulation, exploration, and AI-chat work after
  commit; queued workers subsequently observe that their durable task/session row no longer
  exists. Recommendation and automatic-fix workers receive a bounded Redis cancellation fence
  keyed by operation kind and deleted user, so work admitted on another backend instance also
  stops. Redis failure remains fail-open, while the deleting instance still interrupts its local
  interactive work.
- Database ownership constraints connect every user-owned `user_id` to `app_user` with
  `ON DELETE CASCADE`. Writes that commit first are removed with the account; writes that
  reach the database after deletion are rejected because the parent account no longer exists.
- Every chat-session or chat-message write locks the active user row and rechecks session
  ownership in the same transaction. A stream that reaches a persistence point after account
  deletion therefore stops instead of recreating chat rows.
- `chat_message.session_id` and `chat_session_pre_admission_stop.session_id` cascade from
  `chat_session`; the latter stores at most 64 database-timestamped turn-specific Stop fences,
  each valid for two minutes. Exploration findings use
  a composite `(user_id, fuzz_task_id)` ownership constraint against their task. A late child
  row therefore cannot attach to a missing parent or to another user's task.
- Durable AI continuation, confirmation, and scenario-draft rows use a composite
  `(user_id, session_id)` constraint against their chat session. They cannot be attached to
  another user's session and cascade when that session is deleted.
- Deletes the current user's board layout, device nodes, rules, specifications,
  device templates, the board edit (undo) journal, verification traces/tasks, simulation
  traces/tasks, exploration findings/tasks, AI continuation/confirmation/draft state, and AI
  chat sessions/messages. The [Board edit journal](board.md#boardundoresultdto) contains user
  snapshots required for reversible edits, so it is user content and is removed with the account.
- Deletes the `app_user` row last.

The [authentication filter](overview.md#authentication) rejects tokens whose account
has been deleted, including when Redis is unavailable.

After Hibernate creates the current schema and its ownership indexes, `DatabaseSchemaConstraints`
establishes or verifies cascade ownership constraints. It never repairs or deletes persisted
rows; invalid ownership or a non-cascade constraint fails startup. These constraints remain
necessary for account-deletion races even on a fresh database: the entities store scalar owner
ids rather than JPA associations. On MySQL the same initializer establishes the case- and
accent-sensitive `utf8mb4_0900_as_cs` username collation, independently of the database's default
collation. The MySQL-specific collation is applied here so the common entity mapping also builds
on H2.

```bash
curl -X DELETE http://localhost:8080/api/auth/account \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"password":"password123","confirmation":"testuser"}'
```
