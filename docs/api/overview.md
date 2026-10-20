# API Conventions

Authoritative definition of the cross-cutting API conventions: the `Result<T>`
response envelope, the authentication scheme, and the error/status codes. This is the
**single source of truth** for these three things — every other API document links
here instead of restating them.

Verified against code on 2026-10-15. Source: `dto/Result.java`,
`exception/GlobalExceptionHandler.java`, `configure/JacksonConfig.java`,
`configure/ApplicationTimeConfig.java`,
`component/model/ModelRequestParser.java`, `security/`.

---

## Response envelope — `Result<T>`

Every application JSON REST response is wrapped in `Result<T>`. The exceptions are a
successfully established SSE stream from `POST /api/chat/completions` (see
[chat-sse.md](chat-sse.md)) and the protocol-level empty `406 Not Acceptable` response when
the client accepts neither JSON nor an endpoint's success representation. Synchronous setup
errors from the chat endpoint still use a JSON `Result<T>` envelope and their original HTTP
status, including when the request advertises only `Accept: text/event-stream`.

Successful SMV downloads return a `text/plain` attachment; their errors use the JSON
envelope. See the [download contract](verification.md#downloading-the-smv-model--one-model-per-run-addressed-by-run).

```json
{
  "code": 200,
  "message": "success",
  "data": { }
}
```

| Field | Type | Notes |
| :--- | :--- | :--- |
| `code` | `Integer` | Mirrors the HTTP status code |
| `message` | `String` | `"success"` on success, otherwise an error message |
| `data` | `T` / omitted | Payload; shape depends on the endpoint. Omitted when the backend value is `null`, including void operations. |

`Result` is annotated `@JsonInclude(NON_NULL)`, so `data` is omitted from the JSON when
null. Success responses are produced by `Result.success(data)` (code `200`).

When a domain doc says "**Response**: `SomeDto`", it means that DTO appears under
`data`. When it says "**Response `data`**: `Long`", the payload is that raw value.

### Timestamp format

Java `LocalDateTime` response fields are serialized as ISO-8601 date-times with the offset
for the configured application zone, for example `2026-07-22T12:30:00+08:00`. Clients must
parse the supplied offset and must not append `Z`. Request fields must use the same offset
form, and that offset must be valid for the configured zone at the supplied local time or
deserialization returns `400`. The owning zone and default are documented in the
[configuration reference](../getting-started/configuration.md#application-time).

### Client-supplied `requestId`

Endpoints that start a cancellable interactive search (rule recommendation, fix search) require an
opaque client-generated `requestId`: **8–80 characters, beginning with an ASCII letter or digit, and
otherwise letters, digits, `.`, `_`, `:` and `-`** — that is `^[A-Za-z0-9][A-Za-z0-9._:-]*$`. Use a
fresh random id for every attempt; reusing one across retries makes a late response indistinguishable
from the current one.

### Interactive-request ownership and the `503` contract

For those same endpoints, the request owner, per-user admission, and initial status are acquired
atomically and stay token-fenced, with cancellation records in Redis. Two consequences a client must
handle identically everywhere:

- An **unknown or post-TTL** acquisition result returns **`503`** after token-fenced cleanup, because
  distributed ownership may already exist. It is an *uncertain* outcome, not a failure — re-read
  authoritative status rather than assuming nothing happened.
- Redis being unavailable never silently downgrades to a local-only guarantee once a distributed
  attempt has begun; it fails closed.

Per-endpoint status semantics stay with each endpoint's own doc.

---

## Authentication

Signed JWT (HS256), checked against the current account credentials. Obtain a token via `POST /api/auth/login` (see
[auth.md](auth.md)), then send it on every authenticated request:

```
Authorization: Bearer <token>
```

- `JwtUtil.parseIdentity` validates the signature, expiry, stable user-id subject, and
  non-negative integer `authVersion` claim in one parse. Tokens missing the credential
  version or carrying malformed identity are unauthenticated; obsolete tokens require
  a new login rather than a compatibility fallback.
- The filter reads the account once and authenticates only when its persisted
  `authVersion` matches the token. `app_user.auth_version` starts at zero and advances
  in the same transaction as a password change. This invalidates older credentials
  across backend instances and survives Redis unavailability. A missing account also
  rejects its tokens. The version is internal and is not returned in account profiles.
- Each newly issued token has its own random `jti`, so separate logins in the same
  second do not accidentally share logout revocation.
- Infrastructure failures during account lookup retain their server-error response;
  they do not become invalid-credential responses or trigger a session-expired logout.
- Token lifetime is configured by `JWT_EXPIRATION` (see
  [../getting-started/configuration.md](../getting-started/configuration.md)).
- Logout blacklists the token in Redis. The blacklist is **fail-open**: if Redis is
  down the app keeps serving, but logout-revocation degrades.
- Controllers receive the authenticated user id via the `@CurrentUser Long userId`
  argument; it is never taken from the request body.

The public endpoints (no token required) are `POST /api/auth/register` and
`POST /api/auth/login`. Every other `/api/auth` operation requires a valid bearer token at both the security-filter and
`@CurrentUser` ownership boundaries. The application excludes Spring Boot's generated
default user because authentication is JWT-only.

---

## Error and status codes

Errors are mapped centrally by `GlobalExceptionHandler` to a `Result<T>` whose `code`
equals the HTTP status, except for the empty protocol-level `406` described above. Error
envelopes explicitly use `Content-Type: application/json` instead of allowing a
success-media `Accept` header to obscure the original error. Domain and framework
exceptions and their statuses:

| HTTP | Exception | Meaning |
| :--- | :--- | :--- |
| 400 | `BadRequestException`, `AccountChangeException` | Malformed request / invalid argument; account changes may return a typed field rejection |
| 401 | `UnauthorizedException` | Missing/invalid/expired token |
| 403 | `ForbiddenException` | Authenticated but not allowed (e.g. another user's resource) |
| 404 | `ResourceNotFoundException` | Resource does not exist |
| 405 | `HttpRequestMethodNotSupportedException` | The path exists but does not support the requested HTTP method |
| 406 | `HttpMediaTypeNotAcceptableException` | No acceptable representation can be produced; the response body is intentionally empty |
| 409 | `ConflictException`, `BoardContentConflictException`, `AccountChangeException`, `ChatSessionBusyException`, `TemplateDeletionConflictException`, `DeviceRuntimeConflictException`, `EnvironmentVariableConflictException`, `DataIntegrityViolationException` | State/uniqueness conflict; specialized conflicts and [registration uniqueness conflicts](auth.md#post-apiauthregister) include structured reason data |
| 413 | `RequestBodySizeFilter` | JSON request body exceeds the configured byte limit and is rejected before DTO binding |
| 415 | `HttpMediaTypeNotSupportedException` | The request content type is unsupported |
| 422 | `ValidationException` | Semantic validation failure |
| 429 | `FuzzTaskQuotaExceededException`, `FuzzTaskStorageQuotaExceededException`, `AsyncTaskQuotaExceededException`, `UserOperationBusyException`, `AuthRateLimitException`, `ChatHistoryQuotaExceededException` | Per-user active/stored background-task limit, per-user synchronous-operation limit, authentication or credential-change attempt limit, or stored chat-history capacity reached. Operation admission returns `USER_FORMAL_OPERATION_BUSY` or `USER_CHAT_OPERATION_BUSY`; authentication limits return an `AUTH_*_RATE_LIMIT_REACHED` reason, `ACCOUNT`, `SOURCE`, or `CAPACITY` scope, retry delay, and `Retry-After` header. Chat capacity returns `CHAT_HISTORY_LIMIT_REACHED` as documented in [chat-sse.md](chat-sse.md). |
| 500 | `InternalServerException`, `SmvGenerationException`, `PersistedDataIntegrityException` (and uncaught) | Server-side failure or unusable persisted semantic data |
| 502 | `BadGatewayException` | The configured AI provider replied, but its output could not be parsed as the requested structured result |
| 503 | `ServiceUnavailableException`, `AsyncTaskDispatchOutcomeUnknownException`, `FixApplyPreflightUnavailableException` | Service temporarily unavailable; an async task dispatch whose cleanup could not be confirmed; or an automatic-fix apply preflight that could not confirm model equality. Unknown async dispatch returns `data={ reasonCode: "TASK_DISPATCH_OUTCOME_UNKNOWN", taskKind, taskId }` so the caller can reconcile before retrying. The specialized fix rejection includes `data.reasonCode=FIX_APPLY_PREFLIGHT_UNAVAILABLE` and is guaranteed to occur before the board write. |
| 504 | `SimulationExecutionException` | Synchronous simulation timed out before producing a usable model trace |

Additional mappings: missing required query parameters, bean-validation failures (`@Valid`,
`@NotNull`, etc.), and malformed JSON map to `400`; `IllegalArgumentException` is masked
to a generic `400` and `IllegalStateException` to `500`, so internal detail is not leaked.

Interactive AI execution preserves typed runtime failures. Unexpected checked worker failures
and JVM errors are internal failures, not temporary-capacity `503` responses. Their original
cause and request id are retained in server diagnostics; HTTP error bodies keep the masking
rules above.

Boundary rejections with field diagnostics include `data.errors`, a field-path to actionable-reason
map, while retaining their original HTTP status. This includes diagnosed `BadRequestException`
responses (`400`) and `ValidationException` responses (`422`); a rejection without trustworthy field
diagnostics may omit this map. Specialized content conflicts include `data.reasonCode` and
`data.errors`, with domain-specific details defined by the
[Board import and mutation contract](board.md). Callers must preserve both the stated reason and
field path rather than infer an error category from English message text or HTTP status alone.

JSON scalar coercion is disabled for REST request DTOs. Integer fields require JSON
integer numbers (not quoted strings, booleans, or floats), floating-point fields require
JSON numbers (not quoted strings or booleans), and boolean fields require JSON booleans
(not `0`/`1`, strings, or floats). Type mismatches fail during request deserialization
before bean validation or service-layer semantic checks run. The `400` response names
the safe JSON path (for example `items[0].count`) in both `message` and `data.errors`;
it does not echo the rejected value or a Java implementation type.

Unknown JSON fields are also rejected rather than silently discarded. This applies to
ordinary board mutations, authentication, chat, automatic-fix requests, and other typed
REST request bodies. Their `400` response identifies the unknown field and its nested
path. Verification and simulation use a dedicated strict parser that rejects scene
fields and unknown run options (for example `attackScenario.mod`). Template-manifest endpoints additionally validate the
raw object against `backend/device-template-schema.json` before DTO conversion.

**Message suppression**: `application.yaml` sets `server.error.include-message: never`
and `include-binding-errors: never`, so Spring's `/error` endpoint does not expose
internal exception detail. Error `message` fields returned through
`GlobalExceptionHandler` are deliberately generic for server-side failures
(`InternalServerException` and uncaught exceptions return `"Internal server error"`).

**Exception — `SmvGenerationException` (500)** is intentionally more specific: it
returns a controlled `message` of the form `"[<errorCategory>] <generation error>"`
and also sets `data` to `{ "errorCategory": "<category>" }`, so the frontend can
surface *why* model generation failed (e.g. an invalid template value) without exposing
a stack trace.

**Specialized conflict data (409)**: an active chat session returns
`{ reasonCode: "CHAT_SESSION_BUSY", sessionId }`. A stale or blocked device-template
deletion returns `{ reasonCode, currentPreview }`, where `reasonCode` is
`TEMPLATE_DELETION_PREVIEW_STALE` or `TEMPLATE_DELETION_BLOCKED`. A stale device-runtime
compare-and-set returns `{ reasonCode: "DEVICE_RUNTIME_STALE", currentDevice }` and makes
no write. A stale public Environment Pool compare-and-set returns
`{ reasonCode: "ENVIRONMENT_VARIABLE_STALE", variableName, currentVariable }` and also
makes no write (`currentVariable` is `null` when the item was removed). Clients use
structured data to refresh the exact pending decision instead of parsing the English
message.

**Persisted semantic integrity (500)**: JSON columns that define a model, run, trace,
template, or structured chat-tool message are decoded fail-closed. Missing, blank,
malformed, or unknown JSON fields, mismatched template names, or an unsupported persisted chat role return the
safe message `Stored model data is invalid; the operation was stopped before using an
incomplete model.` with `data={ reasonCode: "PERSISTED_SEMANTIC_DATA_INVALID",
recordType, recordId?, field }`. The backend does not reinterpret corrupt data as an
empty list, default role, or missing model feature. History list endpoints may instead
isolate a damaged row as a documented `dataAvailable=false` placeholder so other valid
history remains accessible.

**Structured validation errors (400/422)** return every available field error, not only
the first one. This applies to DTO/constraint validation (`400`) and service semantic
`ValidationException` failures (`422`). `message` remains a short first-error summary,
while `data` is:

```json
{
  "errors": {
    "nodes[0].id": "Reason for this field",
    "rules[1].conditions": "Reason for this field"
  }
}
```

Clients must account for every entry when an operation validates a compound object such
as a complete scene. Field paths are diagnostic coordinates; ordinary UI translates
them to user-facing item labels and localized failure states. Because legacy validation
reasons are English technical diagnostics rather than locale-stable copy, clients place
the raw field/reason pair in Technical Details unless a domain endpoint supplies a
stable `reasonCode`. Unknown backend free text must not be inserted into a different-
language interface.

**Exception — `SimulationExecutionException` (500/503/504)** means a synchronous
simulation produced no usable model trace. Its controlled message is accompanied by
`data: { reasonCode, logs }`; `reasonCode` distinguishes timeout, interruption, empty
parsed states, and general execution failure. It is an error response, never an empty
success-shaped `SimulationResultDto`.

Generation warnings that do not abort model generation are not HTTP errors. Verification
returns `200` with warning details in `VerificationResultDto.checkLogs`,
`disabledRuleCount`, `skippedSpecCount`, and item-level `generationIssues`; clients must
display each omitted item's label and reason even when
`outcome=SATISFIED`, and also use `modelComplete` to qualify the conclusion.

---

## Related

- Endpoint index: [rest-endpoints.md](rest-endpoints.md)
- Auth contract: [auth.md](auth.md)
- Config (JWT, Redis, CORS): [../getting-started/configuration.md](../getting-started/configuration.md)
