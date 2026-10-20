# Troubleshooting

> Verified against code on 2026-10-15. Source:
> `backend/src/main/resources/application.yaml`,
> `backend/src/main/java/cn/edu/nju/Iot_Verify/component/nusmv/`,
> `backend/src/main/java/cn/edu/nju/Iot_Verify/service/impl/VerificationServiceImpl.java`,
> `backend/src/main/java/cn/edu/nju/Iot_Verify/service/impl/RedisTokenBlacklistService.java`,
> `backend/src/main/java/cn/edu/nju/Iot_Verify/service/UserOperationGuard.java`,
> `backend/src/main/java/cn/edu/nju/Iot_Verify/service/DistributedInteractiveExecutionStore.java`,
> `backend/src/main/java/cn/edu/nju/Iot_Verify/service/InteractiveAiExecutionService.java`,
> `backend/src/main/java/cn/edu/nju/Iot_Verify/service/recommendation/RuleRecommendationService.java`,
> `backend/src/main/java/cn/edu/nju/Iot_Verify/security/SecurityConfig.java`.

Common issues and fixes. For every configuration key mentioned here, see [../getting-started/configuration.md](../getting-started/configuration.md) for the variable, its default, and how it is resolved — this page does not repeat defaults.

## MySQL connection failure

The backend cannot reach the database on startup.

- Confirm MySQL is running: `mysql -u root -p -e "SHOW DATABASES;"`
- Confirm the `iot_verify` database exists (see [../getting-started/installation.md](../getting-started/installation.md)).
- Check the connection settings (`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`) in [../getting-started/configuration.md](../getting-started/configuration.md).

## Redis connection failure

```
Unable to connect to Redis at localhost:6379
```

Redis is optional and runs in fail-open mode: a failed connection does not stop the backend
from starting. Logout token revocation stops working, and per-user operation admission plus
interactive recommendation/fix status and cancellation can fall back to process-local behavior.
For recommendation/fix work, that fallback is allowed only when Redis is unavailable before the
atomic ownership command is attempted. A lost/unknown command result or a success response that
arrives after its lease TTL returns `503` and attempts token-fenced cleanup; retry with a fresh
request id rather than assuming no operation was admitted. Previously issued tokens remain valid
until they expire, and separately load-balanced backend instances cannot coordinate local-fallback
operations while Redis is unavailable. See the authoritative [Redis behavior](../getting-started/configuration.md#redis)
for active-lease handling.

- To restore full behavior, start Redis (`redis-server`, or `docker run -d -p 6379:6379 redis:alpine`).
- Otherwise it is safe to continue on one backend instance with the degraded behavior above.

See `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` in [../getting-started/configuration.md](../getting-started/configuration.md).

## CORS errors

The browser blocks requests from the frontend origin.

- Confirm `CORS_ORIGINS` includes the exact origin (scheme, host, and port) the frontend runs on. See [../getting-started/configuration.md](../getting-started/configuration.md).

## NuSMV execution failure

**Synchronous verification** returns HTTP 200 with `outcome: "INCONCLUSIVE"`,
`modelComplete: false`, and details in `checkLogs`:

```json
{
  "code": 200,
  "data": {
    "outcome": "INCONCLUSIVE",
    "modelComplete": false,
    "traces": [],
    "specResults": [],
    "checkLogs": ["NuSMV execution failed: <error details>"]
  }
}
```

**Asynchronous verification** task status shows `FAILED`:

```json
{
  "status": "FAILED",
  "errorMessage": "NuSMV execution failed: NuSMV exited with code 1: <output details>",
  "checkLogs": ["Generating NuSMV model...", "NuSMV execution failed: <error details>"]
}
```

Checklist:

- Confirm `nusmv.path` (`NUSMV_PATH`) points to the correct NuSMV executable.
- Confirm the version is NuSMV 2.6 or 2.7 and **not nuXmv** (incompatible): `NuSMV -version`. The
  trace parser depends on that range's English output format.
- On Linux running a Windows NuSMV build, set `nusmv.command-prefix` (`NUSMV_PREFIX=wine`) so the executable is launched through Wine.
- Review the specific error in `checkLogs` and, for async tasks, `errorMessage`.

When verification completes and NuSMV per-spec results are available, `specResults`
contains objects shaped as `{ "specId": "...", "outcome": "SATISFIED", "expression": "..." }`.
An emitted property with missing or unreliable parser output uses
`"outcome": "INCONCLUSIVE"`; a property omitted during generation does not appear in
`specResults` and is instead explained by `generationIssues`. Execution failures can
still return an empty array because no trustworthy per-spec result was produced.

See NuSMV keys in [../getting-started/configuration.md](../getting-started/configuration.md) and the model details in [../architecture/nusmv-model.md](../architecture/nusmv-model.md).

## Verification timeout

**Synchronous verification** returns HTTP 200 with `outcome: "INCONCLUSIVE"`,
`modelComplete: false`, and a timeout note in `checkLogs`:

```json
{
  "code": 200,
  "data": {
    "outcome": "INCONCLUSIVE",
    "modelComplete": false,
    "traces": [],
    "specResults": [],
    "checkLogs": ["Verification timed out"]
  }
}
```

**Asynchronous verification** task status shows `FAILED`:

```json
{
  "status": "FAILED",
  "errorMessage": "NuSMV execution failed: NuSMV execution timed out after ...",
  "checkLogs": ["Generating NuSMV model...", "NuSMV execution failed: NuSMV execution timed out after ..."]
}
```

This means the model is too complex or the timeout is too short. Try:

- Raise the per-verification timeout via `NUSMV_TIMEOUT_MS`.
- Reduce the state space: fewer devices and/or rules.
- Lower `NUSMV_MAX_CONCURRENT` to balance concurrency against system resources.
- Prefer async verification (`POST /api/verify/async`) for large models so the request does not block.

See the NuSMV timeout/concurrency keys in [../getting-started/configuration.md](../getting-started/configuration.md).

## AI recommendation failed

A provider HTTP `404` with `reason=MODEL_NOT_FOUND` means the configured model is unavailable
to that endpoint/account; it happens before candidate validation. Check the effective
[AI model configuration](../getting-started/configuration.md#llm-ai) and the provider's available
models before retrying. A catalog entry does not establish that the current credential's group
has an active account supporting that model; confirm a completion through the same protocol.
The provider log recognizes `model_not_found` in either the error type
or code, while other values remain `UNCLASSIFIED`; raw provider messages and unknown codes are
not logged. The stream-finished log records the effective request model, including a recommendation
model override. A `404` alone can also mean an incorrect API route and does not prove a missing model.

A provider completion log confirms only that model text arrived. Rule recommendations still
need JSON parsing and capability/semantic validation. The rule service logs the start of this
step and its retained/filtered counts without logging candidate content. Unexpected worker
failures log `userId`, `requestId`, the last observed stage, and the original exception stack.
The exception classification and HTTP masking rules are owned by
[API error handling](../api/overview.md#error-and-status-codes).

If the same interval contains `CodeCache is full` or `Out of space in CodeCache`, inspect the
running JVM with `jcmd <pid> Compiler.codecache` and `jcmd <pid> VM.flags`. Code cache is distinct
from Java heap. Space may later be reclaimed and compilation restarted, so a successful retry
does not establish that the original request or its JSON was valid. A warning alone cannot
identify the exact exception thrown by an earlier request whose cause was not logged.

The project's [development JVM launch](../getting-started/configuration.md#development-jvm-launch)
uses normal tiered compilation to avoid the Maven startup optimization's reduced code cache.
Restart after changing launch configuration and verify the new JVM's flags; rebuilding underneath
an existing process does not apply it. For explicit JVM limits, inspect the actual code-cache
capacity and pressure before changing them.

## JWT token expired

```json
{"code": 401, "message": "Authentication required"}
```

Missing, invalid, expired, or revoked tokens produce this response. Log in again;
the message alone does not distinguish expiration. See [API authentication](../api/overview.md#authentication)
and the [JWT configuration](../getting-started/configuration.md#authentication-jwt).

## Test compilation cannot find a class that exists

```text
无法访问SimulationTraceMapper / cannot access SimulationTraceMapper
  找不到SimulationTraceMapper的类文件 / class file for SimulationTraceMapper not found
```

A present `.class` file does not establish that the source or build is correct.
Check for concurrent writers and a running backend before cleaning or rebuilding;
follow the [build-environment diagnostics](../development/known-traps.md#2-build-environment).

## Related

- Installation: [../getting-started/installation.md](../getting-started/installation.md)
- Deployment: [../getting-started/deployment.md](../getting-started/deployment.md)
- Configuration reference: [../getting-started/configuration.md](../getting-started/configuration.md)
- Documentation map: [../README.md](../README.md)
