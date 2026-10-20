# Architecture Overview

System topology, the front/back boundary, and the backend package layout. Deep dives
link out to the focused architecture documents.

Verified against code on 2026-10-18. Source: `backend/src/main/java/cn/edu/nju/Iot_Verify/`,
`frontend/src/`.

---

## Focused Contracts

- [Device identity authority](device-identity.md): canonical device ids, display labels,
  rule/spec/AI references, NuSMV varName normalization, and the no-fallback policy.
- [Data authority model](data-authority-model.md): field-level ownership and downstream
  consumption for devices, rules, specs, edges, model requests, traces, tasks, and fix.
- [Counterexample exploration](fuzzing-flow.md): bounded search, reproducibility,
  supported finite semantics, and its boundary from formal verification.

---

## Topology

```
┌─────────────┐   HTTP / SSE    ┌──────────────────────┐   process    ┌─────────┐
│  Vue 3 SPA  │ ──────────────▶ │   Spring Boot API    │ ───────────▶ │  NuSMV  │
│ (Vite:3000) │ ◀────────────── │      (:8080)         │ ◀─────────── │ 2.6–2.7 │
└─────────────┘   Result<T>     └──────────┬───────────┘   stdout      └─────────┘
                                           │
                                  ┌────────┴────────┐
                                  │ MySQL   Redis   │  (Redis fail-open)
                                  │  LLM endpoint   │  (OpenAI-compatible; AI, SSE streaming)
                                  └─────────────────┘
```

- **Frontend**: Vue 3 + TypeScript + Vite SPA. Talks to the backend over HTTP
  (`Result<T>` JSON) and one SSE stream for chat. See
  [../guides/frontend-integration.md](../guides/frontend-integration.md).
- **Backend**: Spring Boot REST API. Owns auth, canvas persistence, NuSMV
  orchestration, auto-fix, and the AI assistant.
- **NuSMV**: external model checker invoked as a subprocess (semaphore-bounded).
- **Stores**: MySQL (persistence), Redis (JWT blacklist, fail-open), and an
  OpenAI-compatible LLM endpoint (AI assistant and recommendations), reached through the
  `LlmProvider` abstraction in `component/ai/`.

---

## Backend package layout

Base package `cn.edu.nju.Iot_Verify`:

```
controller/          REST controllers (Result<T> wrapper)
service/ , service/impl/   business logic
  recommendation/    typed recommendation and rule-check implementations shared by REST and chat
component/
  nusmv/
    generator/       SMV model generation (device modules + main + specs)
    executor/        NusmvExecutor — subprocess exec, semaphore concurrency, timeout
    parser/          SmvTraceParser — counterexample parsing
    fixer/           FaultLocalizer + parameter/condition/rule-removal fix strategies
  fuzz/              deterministic bounded path search + finite specification monitor
  aitool/            registered AI tools (board/node/rule/scenario/spec/template/simulation/verification/fuzz/chat/preparation)
  ai/                LLM abstraction: domain model + LlmProvider (OpenAiLlmProvider adapter)
                     + LlmChatService / PromptCompletionService; LlmMessageCodec owns history encoding
dto/                 request/response DTOs
po/                  JPA entities
repository/          Spring Data repositories
security/            JWT + Spring Security (@CurrentUser, JwtAuthenticationFilter)
configure/           config classes, thread pools, ProductionSafetyCheck
exception/           exception hierarchy + GlobalExceptionHandler
util/                utilities
resources/
  application.yaml              main config (env-var overridable)
  deviceTemplate/               default device template JSON (seeded into DB per user)
```

`backend/device-template-schema.json` lives at the module root, **not** under `resources/`; a second
`<resource>` block in `pom.xml` copies it onto the classpath at build time, which is how
`DeviceTemplateSchemaValidator` loads it as `ClassPathResource("device-template-schema.json")`. Edit
the module-root copy — a file created under `resources/` would shadow nothing and fight the build.

Key service implementations: `AuthServiceImpl`, `BoardStorageServiceImpl`,
`VerificationServiceImpl`, `SimulationServiceImpl`, `FuzzServiceImpl`, `FixServiceImpl`, `ChatServiceImpl`,
`NodeServiceImpl`, `DeviceTemplateServiceImpl`, plus `RedisTokenBlacklistService` and
the `AbstractAsyncTaskService` base for async verify/simulate tasks.

---

## Frontend layout

```
frontend/src/
  api/       http.ts (axios) + auth/board/chat/rules/simulation/fuzzing modules
  types/     TypeScript contracts (auth, device, node, edge, rule, spec, verify, fuzzing, …)
  stores/    reactive state (auth, chat)
  router/    routes + auth guard
  views/     Landing / Board / Account / NotFound
  components/ CanvasBoard, ChatView, ControlCenter, SystemInspector,
              TraceHistoryPanel, SimulationTimeline, FixResultDialog,
              RuleBuilder, DeviceDialog, …
  assets/    static assets + i18n (zh-CN / en)
```

The public landing page owns the integrated login/register panel via query modes such
as `/?mode=login` and `/?mode=register`. Standalone `Login`, `Register`, and
`TemplateCreate` routes/views are intentionally absent in the current development
surface.

Canvas presentation reuses reactive template-alias indexes; the first matching template retains
precedence. Bundled-icon fallback lookup is indexed once from the static asset inventory. Sparse
playback builds current/previous device and variable indexes in one historical pass, preserving
first-match alias order and frozen reading objects. Pan and zoom reuse those indexes. The model
comparison source reads semantic node fields explicitly so geometry and display-name updates do
not serialize the scene. Inspector tab totals read collection sizes, while only the active
inventory formats and filters its rows.

The private, lazy `/account` page owns account form drafts and combines profile,
credential, session, and account-deletion actions. The workspace has one account entry;
sign-out remains a confirmed action within that page. The existing auth store owns the
displayed login identity. Account reads and credential mutations stay
in `AuthController` / `AuthServiceImpl`, with the same user repository and row lock as
other account operations. Credential validity is checked against the account at request
authentication; see the [authentication scheme](../api/overview.md#authentication).
Adding another account capability does not require a parallel settings store or service.

---

## Cross-cutting concerns

- **Auth & errors**: [../api/overview.md](../api/overview.md).
- **Async tasks**: verification/simulation support sync and async modes; bounded
  exploration is background-only. All expose progress/cancellation and use atomic
  terminal status writes.
- **Concurrency**: seven configurable thread pools (`ThreadConfig`); NuSMV runs are bounded by a
  semaphore (`NUSMV_MAX_CONCURRENT`). See
  [../getting-started/configuration.md](../getting-started/configuration.md).

## Where to go next

- Verification request → result pipeline:
  [verification-flow.md](verification-flow.md)
- Bounded candidate-path search: [fuzzing-flow.md](fuzzing-flow.md)
- SMV model generation internals: [nusmv-model.md](nusmv-model.md)
- Spec formulas & P1–P5: [spec-templates.md](spec-templates.md)
- Auto-fix: [auto-fix.md](auto-fix.md)
- Endpoint index: [../api/rest-endpoints.md](../api/rest-endpoints.md)
