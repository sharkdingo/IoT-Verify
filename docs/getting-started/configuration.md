# Configuration Reference

**Single source of truth** for every runtime configuration option. Any other document
that needs to mention a config key or default value must link here rather than copy
the value.

Backend options are read from `backend/src/main/resources/application.yaml` using the
`${ENV_VAR:default}` pattern, so every value can be overridden by an environment
variable without editing the file. Frontend options are Vite build-time variables (see
the [Frontend](#frontend-vite) section at the end).

Verified against code on 2026-07-31. Source:
`backend/src/main/resources/application.yaml`, `configure/ThreadPoolConfig`,
`configure/FuzzAdmissionConfig`, `configure/AsyncTaskAdmissionConfig`,
`configure/ChatExecutionConfig`, `configure/NusmvConfig`, `configure/OperationAdmissionConfig`,
`configure/ProductionSafetyCheck`, `configure/ApplicationTimeConfig`,
`security/RequestBodySizeFilter`, `security/AuthRateLimitGuard`, `service/UserOperationGuard`,
`service/DistributedInteractiveExecutionStore`,
`frontend/src/api/`, `frontend/.env.example`.

---

## Database (MySQL)

| Env var | Default | Notes |
| :--- | :--- | :--- |
| `DB_URL` | `jdbc:mysql://localhost:3306/iot_verify?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf-8&allowPublicKeyRetrieval=true` | JDBC connection string |
| `DB_USERNAME` | `root` | Database user |
| `DB_PASSWORD` | `sharkdingo123` | Database password. **Unsafe default — must be overridden in production** (enforced by `ProductionSafetyCheck`). |
| `JPA_SHOW_SQL` | `false` | Log SQL statements |

`spring.jpa.hibernate.ddl-auto` is fixed to `update` (tables auto-created on first
start); Hibernate detects the MySQL dialect from the live JDBC connection. Open Session
in View is disabled, so service methods must materialize response DTOs inside their
transaction instead of issuing implicit queries during HTTP serialization.

## Application time

| Env var | Default | Notes |
| :--- | :--- | :--- |
| `IOT_VERIFY_TIME_ZONE` | `Asia/Shanghai` | IANA time-zone id used for application wall-clock persistence and offsets on API timestamps. Keep it aligned with the JDBC `serverTimezone` and the database session time zone. Invalid ids fail startup. |

The configured zone is installed as the JVM default before persistence infrastructure is
initialized. REST fields backed by Java `LocalDateTime` are emitted as ISO-8601 values with
the zone's matching UTC offset (for example `2026-07-22T12:30:00+08:00`). Requests must
include an offset, and that offset must be valid for the configured zone at the supplied local
time; a missing or mismatched offset is rejected with `400` instead of silently changing the
represented time.
At daylight-saving overlaps the earlier valid offset is used for serialization. A
nonexistent gap wall time is preserved with the offset immediately before the transition,
so a serialized response can be submitted unchanged.

## Server

| Env var | Default | Notes |
| :--- | :--- | :--- |
| `SERVER_PORT` | `8080` | Backend HTTP port |
| `IOT_VERIFY_MAX_REQUEST_BYTES` | `4194304` | Maximum JSON request-body size in bytes (4 MiB). Declared and chunked oversized requests return `413` before JSON binding. The body-buffering filter runs after Spring Security, so unauthorized protected requests are rejected before allocating the maximum body. The production reverse proxy should enforce the same or a stricter limit. |
| `IOT_VERIFY_MAX_SCENE_REQUEST_BYTES` | `67108864` | Dedicated maximum for the authenticated full-scene commands `POST /api/board/scene` and `POST /api/board/batch` (64 MiB), allowing self-contained template snapshots and embedded icons to round-trip. All other JSON endpoints retain `IOT_VERIFY_MAX_REQUEST_BYTES`. The browser applies the same 64 MiB scene-file boundary. |

`server.error.include-message` and `include-binding-errors` are fixed to `never` to
prevent the Spring `/error` endpoint from leaking internal exception detail.
Spring Boot also interprets a generic process environment variable named `DEBUG`; even a
value intended for another tool can enable verbose framework logging. Remove `DEBUG` from
the backend process environment in production and configure intentional logger levels
through Spring's `logging.level.*` properties instead.

## Authentication (JWT)

| Env var | Default | Notes |
| :--- | :--- | :--- |
| `JWT_SECRET` | `iot-verify-secret-key-must-be-at-least-256-bits-long-for-hs256` | HS256 signing key (≥256 bits). **Unsafe default — must be overridden in production.** |
| `JWT_EXPIRATION` | `86400000` | Token lifetime in ms (24h) |
| `AUTH_LOGIN_RATE_LIMIT_PER_MINUTE` | `10` | Maximum valid login requests per normalized account identifier and backend instance in a one-minute fixed window. |
| `AUTH_REGISTER_RATE_LIMIT_PER_HOUR` | `5` | Maximum valid registration requests per phone number and backend instance in a one-hour fixed window. |
| `AUTH_SOURCE_LOGIN_RATE_LIMIT_PER_MINUTE` | `120` | Higher source-IP ceiling across account identifiers, preventing identifier rotation without coupling ordinary users behind one NAT to the low account limit. |
| `AUTH_SOURCE_REGISTER_RATE_LIMIT_PER_HOUR` | `60` | Higher source-IP ceiling across phone numbers. |
| `AUTH_RATE_LIMIT_MAX_WINDOWS` | `65536` | Hard per-instance ceiling across active account and source-IP windows. At capacity the guard rejects new window identities and preserves active counters; it never evicts an active bucket to admit a rotating identifier. Values below `2` are clamped to `2`. |

The request limits apply after DTO validation and before credential/database work. Excess
attempts return structured `429` data (`reasonCode`, `scope`, `retryAfterSeconds`) plus a
`Retry-After` header exposed through CORS. `scope` is `ACCOUNT` or `SOURCE` for a consumed
attempt window and `CAPACITY` when a new identity cannot be tracked without evicting an
active window. Capacity retry timing follows the earliest tracked expiry rather than
claiming that the caller exhausted a fresh full window. The guard is intentionally process-local;
multi-instance deployments must also enforce a distributed ceiling at the TLS reverse
proxy or API gateway. New passwords contain 10–64 characters and must encode to at most
72 UTF-8 bytes, matching BCrypt's input boundary.

## Redis

Redis backs the JWT-token blacklist (logout revocation), per-user operation admission, and
the short-lived interactive recommendation/fix execution registry. These paths run in
**fail-open** mode: if Redis is unavailable the app still starts, but logout revocation and
cross-instance coordination degrade to their documented process-local behavior. Interactive
recommendation/fix acquisition uses that local fallback only when a read-only probe establishes
that Redis is unavailable before any ownership write is attempted. Once its atomic acquisition
command has been dispatched, an exception, unknown result, or confirmation arriving after the
30-second lease TTL returns HTTP `503` and attempts token-fenced cleanup instead of starting a
second process-local owner whose distributed state is uncertain.

| Env var | Default | Notes |
| :--- | :--- | :--- |
| `REDIS_HOST` | `localhost` | |
| `REDIS_PORT` | `6379` | |
| `REDIS_PASSWORD` | *(empty)* | |
| `REDIS_DATABASE` | `0` | Database index |
| `OPERATION_ADMISSION_LEASE_HEARTBEAT_MS` | `30000` | Delay between token-checked renewals of active per-user verification/simulation/fix/chat admission leases. Range `1000..600000` ms. |
| `SCHEDULER_POOL_SIZE` | `6` | Shared Spring scheduler threads for independent lease heartbeats, interactive cancellation polling, AI-state cleanup, and NuSMV artifact cleanup. Keep this above `1` so a slow maintenance pass cannot delay every heartbeat. |

Fixed pool settings: `timeout 3000ms`, `max-active 16`, `max-idle 8`, `min-idle 2`,
`max-wait 2000ms`.

Redis also coordinates per-user admission for synchronous formal work and assistant
streams. One verification/simulation/fix operation and up to the configured number of assistant
streams may run per user. Active Redis leases are renewed only while their owning in-process lease is
open; renewal and release both compare the random owner token before changing the key.
When Redis is unavailable, these limits remain enforced within each backend process but
cannot coordinate across instances; request processing remains fail-open.
An already-acquired lease tolerates transient renewal failures, but if ownership is
explicitly lost or cannot be confirmed for a complete TTL, the owning worker is interrupted
and the operation fails rather than overlapping a replacement worker.

Standalone recommendation and interactive automatic-fix request ids are registered across
instances with token-fenced owner, user, status, and cancellation keys. Owner, optional
per-user exclusivity, and initial status are acquired in one Redis script, so a request cannot
retain only a subset after an ordinary command result. The pre-acquisition fallback and
uncertain-result `503` boundary are defined above. An active entry has a renewable 30-second
lease, and its final `FINISHED` status remains readable for 15 seconds. A poll response confirms
ownership from its monotonic call-start time; a response arriving only after the TTL stops the
worker even when Redis reported success. Cancellation stays registered and renewed until the
owning callable exits; an expired or reused request id cannot let an old worker publish status
over its replacement. A locally accepted outage-mode worker remains controllable on that
instance, but another instance cannot observe or cancel it. Each new client attempt must
therefore generate a fresh request id.

These coordination scripts operate in the single Redis keyspace configured above. Redis
Cluster is not supported by the current single-host configuration and multi-key scripts.

## CORS

| Env var | Default | Notes |
| :--- | :--- | :--- |
| `CORS_ORIGINS` | `http://localhost:3000,http://127.0.0.1:3000,http://localhost:3001,http://127.0.0.1:3001,http://localhost:5173,http://127.0.0.1:5173,http://localhost:5174,http://127.0.0.1:5174,http://localhost:5175,http://127.0.0.1:5175,http://localhost:5176,http://127.0.0.1:5176` | Comma-separated allowed origins. The defaults cover the primary Vite dev port and common fallback ports for local development; set a tighter value in deployed environments. |

## LLM (AI)

Any OpenAI-compatible endpoint — the official OpenAI API, a self-hosted gateway, or a
relay. Point `IOT_VERIFY_OPENAI_BASE_URL` at the endpoint and supply the matching key.

| Env var | Default | Notes |
| :--- | :--- | :--- |
| `IOT_VERIFY_OPENAI_API_KEY` | `your_api_key_here` | API key for the endpoint. **Placeholder default — must be overridden in production.** |
| `IOT_VERIFY_OPENAI_MODEL` | `gpt-5.6-luna` | Model id / deployment name sent to the endpoint |
| `IOT_VERIFY_OPENAI_RECOMMENDATION_MODEL` | *(empty; reuses `IOT_VERIFY_OPENAI_MODEL`)* | Optional model dedicated to device/rule/specification/scenario recommendations |
| `IOT_VERIFY_OPENAI_BASE_URL` | `https://api.openai.com/v1` | OpenAI-compatible base URL (official API or relay) |
| `LLM_PROVIDER` | `openai` | Provider implementation selector (currently only `openai`) |
| `LLM_TIMEOUT_MINUTES` | `5` | AI request timeout (minutes) |

On Windows, changing a User environment variable does not update already-running
terminals, IDEs, or backend processes. Restart the process that launches Spring Boot, or
set the process value explicitly before startup, for example
`$env:IOT_VERIFY_OPENAI_MODEL = "gpt-5.6-luna"`. The backend only sees the environment of the
process that starts it.

## Chat Streaming

| Env var | Default | Notes |
| :--- | :--- | :--- |
| `CHAT_SSE_TIMEOUT_MS` | `3600000` | Total SSE emitter lifetime for `/api/chat/completions` (60 minutes by default); keep it higher than `LLM_TIMEOUT_MINUTES` so long multi-step tasks and provider errors remain visible. |
| `CHAT_MAX_CONCURRENT_SESSIONS_PER_USER` | `4` | Maximum independently running assistant conversations admitted for one user. Redis enforces the limit across instances; the fail-open fallback enforces it per process. Minimum `1`. |
| `CHAT_MAX_TOOL_ROUNDS` | `64` | Emergency runaway guard for one assistant request, not a normal task budget. Product flows should finish or stop on confirmation/result safety before this value. Minimum `8`. |
| `CHAT_MAX_STAGNANT_ROUNDS` | `2` | Stop after this many consecutive rounds repeat the exact same tool calls and results. A changed call or changed result resets the counter. Minimum `1`. |
| `CHAT_MAX_TOOL_RESULT_BYTES` | `49152` | Maximum UTF-8 bytes in one AI tool result before it becomes a structured `TOOL_RESULT_TOO_LARGE` unavailable result. Minimum `4096`. |
| `CHAT_MAX_TOOL_CALLS_PER_ROUND` | `16` | Maximum tool calls accepted from one model planning response. A larger response executes none of its calls. Minimum `1`. |
| `CHAT_MAX_MESSAGES_PER_SESSION` | `5000` | Stored-row ceiling for one conversation. It must reserve `2 + CHAT_MAX_TOOL_ROUNDS * (1 + CHAT_MAX_TOOL_CALLS_PER_ROUND)` rows for a complete turn. |
| `CHAT_HISTORY_CHAR_LIMIT` | `32000` | Maximum coherent recent conversation context supplied to the model, measured in Java characters. Complete tool-call/result blocks remain atomic at the boundary. Minimum `4000`. |
| `CHAT_EXECUTION_LEASE_TTL_MS` | `30000` | Lifetime of the cross-instance chat execution lease without a successful heartbeat. A crashed/restarted worker therefore stops reporting the session as active after at most this interval. Minimum `3000`; must be at least twice the heartbeat interval. |
| `CHAT_EXECUTION_LEASE_HEARTBEAT_MS` | `10000` | Fixed delay between execution-lease renewals and expired-lease cleanup passes. Renewal is conditional on the same session/user/execution id, so an old worker cannot reclaim a replaced lease. Minimum `1000`. |
| `AI_SESSION_STATE_CLEANUP_MS` | `60000` | Fixed delay between database cleanup passes for expired AI task-continuation, scenario-draft, and protected-action state. Active reads also reject and remove expired rows. |

## NuSMV

| Env var | Default | Notes |
| :--- | :--- | :--- |
| `NUSMV_PATH` | `D:/NuSMV/NuSMV-2.7.1-win64/NuSMV-2.7.1-win64/bin/NuSMV.exe` | Path to the NuSMV executable. Requires NuSMV 2.6–2.7 (**not** nuXmv). |
| `NUSMV_PREFIX` | *(empty)* | Command prefix, e.g. `wine` when running a Windows NuSMV build on Linux |
| `NUSMV_TIMEOUT_MS` | `120000` | Per-verification timeout (ms) |
| `NUSMV_MAX_CONCURRENT` | `6` | Max concurrent NuSMV processes (semaphore) |
| `NUSMV_ACQUIRE_PERMIT_TIMEOUT_MS` | `10000` | Timeout to acquire a concurrency permit (ms) |
| `NUSMV_MAX_OUTPUT_BYTES` | `4194304` | Maximum retained bytes for each NuSMV stdout/stderr stream. Pipes are still fully drained; excess output is replaced by a truncation marker. |
| `NUSMV_TEMP_RETENTION_HOURS` | `24` | Maximum age of retained `nusmv_*` diagnostic directories under the JVM temporary directory. |
| `NUSMV_MAX_RETAINED_TEMP_DIRECTORIES` | `200` | Maximum number of retained `nusmv_*` diagnostic directories. Active directories are protected by an OS file lock; among inactive directories, the oldest eligible entries are removed first after a 10-minute grace period. Cleanup also removes inactive orphan lock markers left after another component already deleted its artifact directory. |
| `NUSMV_TEMP_CLEANUP_MS` | `300000` | Fixed delay in milliseconds between diagnostic-directory cleanup passes; minimum 10000. |

## Auto-fix

| Env var | Default | Notes |
| :--- | :--- | :--- |
| `FIX_MAX_ATTEMPTS` | `20` | Max main candidate attempts per fix strategy; a multi-threshold parameter search shares this budget between one-threshold probes and joint solving, refinement is separately bounded by `FIX_MAX_REFINE_ATTEMPTS`, and the whole pipeline by `FIX_TIMEOUT_MS` |
| `FIX_MAX_CANDIDATES_PER_RULE` | `5` | Max candidate fixes per rule |
| `FIX_MAX_REFINE_ATTEMPTS` | `10` | Max refinement-loop iterations for each listed parameter alternative |
| `FIX_TIMEOUT_MS` | `300000` | Overall fix timeout / soft deadline (ms) |

## Device Templates

| Env var | Default | Notes |
| :--- | :--- | :--- |
| `DEVICE_TEMPLATE_SCHEMA_PATH` | `device-template-schema.json` | Optional filesystem override for the canonical device-template manifest schema. The repository source of truth is `backend/device-template-schema.json`; the Maven build also packages that file onto the backend classpath. Leave this unset unless a deployment intentionally supplies the same schema from a mounted path. |

## Counterexample-search admission

| Env var | Default | Notes |
| :--- | :--- | :--- |
| `FUZZ_MAX_ACTIVE_TASKS_PER_USER` | `2` | Maximum combined `PENDING` and `RUNNING` counterexample-search tasks owned by one user. Must be at least `1`; excess submissions return HTTP 429. |
| `FUZZ_MAX_STORED_TASKS_PER_USER` | `100` | Maximum total counterexample-search task rows owned by one user, including active, completed, failed, and cancelled rows. Must be at least the active-task limit; users at the limit must delete old history or failed/cancelled tasks before submitting. |

The per-user checks lock that user's database row and count both total stored tasks and
active tasks in the same transaction that freezes and inserts the new task, so concurrent
submissions cannot bypass either limit across backend instances. Each backend process
also reserves one in-memory admission permit before reading the Board. The process-wide
permit count is derived from
`THREAD_POOL_FUZZ_TASK_MAX + THREAD_POOL_FUZZ_TASK_QUEUE` and is held until the worker
ends; this keeps snapshot creation from outrunning the executor's configured capacity.

## Verification and simulation task admission

| Env var | Default | Notes |
| :--- | :--- | :--- |
| `VERIFICATION_MAX_ACTIVE_TASKS_PER_USER` | `2` | Maximum combined `PENDING` and `RUNNING` async verification tasks owned by one user. Must be at least `1`. |
| `VERIFICATION_MAX_STORED_TASKS_PER_USER` | `100` | Maximum total verification task/run rows owned by one user, including synchronous completed runs. Must be at least the active-task limit. |
| `SIMULATION_MAX_ACTIVE_TASKS_PER_USER` | `2` | Maximum combined `PENDING` and `RUNNING` async simulation tasks owned by one user. Must be at least `1`. |
| `SIMULATION_MAX_STORED_TASKS_PER_USER` | `100` | Maximum total logical simulation runs owned by one user: task rows plus saved traces that are not already referenced by a task. Must be at least the active-task limit. |

Each async submission locks the owning user row and checks stored and active counts in the
same transaction that inserts the task, so concurrent requests cannot bypass the quota.
Synchronous verification and saved-simulation requests preflight the same stored-run limit
before starting NuSMV and recheck it under the user lock when persisting the result.
Their history-list endpoints use these same configured limits as bounded query sizes, so
raising a stored-run limit does not make otherwise valid retained rows invisible.
Excess submissions return HTTP 429 with stable `reasonCode`, `taskKind`, `quotaType`,
`taskCount`, and `maxTasksPerUser` data. Users free stored capacity by deleting old
completed runs or dismissing failed/cancelled tasks through the documented APIs.

## Thread pools

Seven pools, each with the same four parameters. Env-var pattern:
`THREAD_POOL_{NAME}_{PARAM}` where `PARAM` ∈ `CORE`, `MAX`, `QUEUE`, `AWAIT`.

Concrete env vars are: `THREAD_POOL_CHAT_CORE`, `THREAD_POOL_CHAT_MAX`,
`THREAD_POOL_CHAT_QUEUE`, `THREAD_POOL_CHAT_AWAIT`, `THREAD_POOL_VERIFICATION_TASK_CORE`,
`THREAD_POOL_VERIFICATION_TASK_MAX`, `THREAD_POOL_VERIFICATION_TASK_QUEUE`,
`THREAD_POOL_VERIFICATION_TASK_AWAIT`, `THREAD_POOL_SYNC_VERIFICATION_CORE`,
`THREAD_POOL_SYNC_VERIFICATION_MAX`, `THREAD_POOL_SYNC_VERIFICATION_QUEUE`,
`THREAD_POOL_SYNC_VERIFICATION_AWAIT`, `THREAD_POOL_SYNC_SIMULATION_CORE`,
`THREAD_POOL_SYNC_SIMULATION_MAX`, `THREAD_POOL_SYNC_SIMULATION_QUEUE`,
`THREAD_POOL_SYNC_SIMULATION_AWAIT`, `THREAD_POOL_SIMULATION_TASK_CORE`,
`THREAD_POOL_SIMULATION_TASK_MAX`, `THREAD_POOL_SIMULATION_TASK_QUEUE`,
`THREAD_POOL_SIMULATION_TASK_AWAIT`, `THREAD_POOL_FUZZ_TASK_CORE`,
`THREAD_POOL_FUZZ_TASK_MAX`, `THREAD_POOL_FUZZ_TASK_QUEUE`, and
`THREAD_POOL_FUZZ_TASK_AWAIT`, plus `THREAD_POOL_INTERACTIVE_AI_CORE`,
`THREAD_POOL_INTERACTIVE_AI_MAX`, `THREAD_POOL_INTERACTIVE_AI_QUEUE`, and
`THREAD_POOL_INTERACTIVE_AI_AWAIT`.

| Pool (`NAME`) | CORE | MAX | QUEUE | AWAIT (s) |
| :--- | :--- | :--- | :--- | :--- |
| `CHAT` | 10 | 50 | 200 | 30 |
| `INTERACTIVE_AI` | 2 | 4 | 8 | 30 |
| `VERIFICATION_TASK` | 4 | 8 | 40 | 60 |
| `SYNC_VERIFICATION` | 4 | 4 | 16 | 30 |
| `SYNC_SIMULATION` | 4 | 4 | 16 | 30 |
| `SIMULATION_TASK` | 4 | 8 | 40 | 60 |
| `FUZZ_TASK` | 2 | 4 | 20 | 60 |

Example override: `THREAD_POOL_CHAT_CORE=20`.

---

## Production safety

When `spring.profiles.active` contains `prod` or `production` (case-insensitive),
`ProductionSafetyCheck` (`@PostConstruct`) refuses to start if any of these still hold
their unsafe default: `JWT_SECRET`, `DB_PASSWORD`, `IOT_VERIFY_OPENAI_API_KEY`. Override all
three before deploying.

The case-insensitive match pins `Locale.ROOT`, and that is load-bearing rather than incidental: with the
JVM's default locale, a Turkish server folds `PRODUCTION` to `productıon` (dotless ı), which matches
neither profile name — so this guard would not fire and the application would boot in production with
every default above still in place. `PRODUCTION_PROFILES` on `ProductionSafetyCheck` is the single owner
of "which profiles are production"; `JwtUtil`'s insecure-JWT-secret warning reads the same set, so the
warning and the hard failure cannot disagree about what production means.
`component/nusmv/LocaleSensitiveComparisonTest` fails if any case fold that decides a keyword drops the
locale.

---

## Frontend (Vite)

Build-time variables read via `import.meta.env`. Copy `frontend/.env.example` to
`frontend/.env` to override; `.env` is gitignored, `.env.example` is committed.

| Variable | Default | Notes |
| :--- | :--- | :--- |
| `VITE_API_BASE_URL` | *(empty)* | Backend origin, read by both the axios client (`src/api/http.ts`, which appends `/api`) and the chat SSE call (`src/api/chat.ts`). **Leave empty** for same-origin setups: requests use a relative `/api` proxied by the Vite dev server (`vite.config.ts`) or a production reverse proxy (Nginx). Set an absolute URL (e.g. `https://api.example.com`) only for cross-origin deployments. |
