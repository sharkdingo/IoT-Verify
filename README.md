# IoT-Verify

A formal-verification platform for smart-home IoT systems. Build a device topology on a
visual canvas, define automation rules and safety specifications, and have the NuSMV
model checker verify them formally — with counterexample-trace analysis and automatic
fix suggestions when a specification is violated.

## Features

- **Visual canvas** — drag-and-drop device modeling with 40+ predefined smart-home
  device templates; create device instances and connections freely.
- **Authoritative device-template schema** — `backend/device-template-schema.json`
  defines the manifest contract used by REST import, AI template creation, default
  templates, and documentation.
- **Rule engine** — IFTTT-style multi-condition automation rules (conditions → command).
- **Safety specifications** — 7 templates (Always / Eventually / Never / Immediate /
  Response / Persistence / Safety) mapped to CTL/LTL formulas.
- **NuSMV formal verification** — auto-generates an SMV model and runs NuSMV, with
  synchronous/asynchronous tasks, progress tracking, and cancellation.
- **Counterexample analysis** — parses NuSMV counterexamples into a step-by-step
  state timeline.
- **Bounded counterexample exploration** — a reproducible, HAFuzz-inspired background
  search finds candidate violating paths before formal verification without presenting
  budget exhaustion as a safety proof.
- **Automatic fix** — parameter adjustment, condition adjustment, and permanent rule removal,
  each candidate re-verified before it is offered.
- **Attack simulation** and **interactive simulation** (random N-step traces).
- **AI assistant** — any OpenAI-compatible LLM endpoint with SSE-streamed final replies
  and [built-in tools](docs/api/ai-tools.md); device / rule / specification recommendations, confirmed
  atomic application of a generated full-scene draft, and confirmed bundled-template reset.
- **Bilingual UI** — full zh-CN / en internationalization.

## Tech stack

- **Backend**: Java 17, Spring Boot 3.5.7, Spring Security + JWT, Spring Data JPA,
  MySQL, Redis, NuSMV 2.6–2.7, OpenAI Java SDK (any OpenAI-compatible endpoint).
- **Frontend**: Vue 3 + TypeScript, Vite, Tailwind CSS, Element Plus, Ant Design icons,
  Vue Router, Vue I18n.

## Quick start

Prerequisites: JDK 17+, Node.js 20.19+ on Node 20 or Node.js 22.12+, Maven 3.6+,
MySQL 8.0+, NuSMV 2.6–2.7
(**not** nuXmv); Redis 6.0+ optional (fail-open).

```bash
# 1. Create the database
mysql -u root -p -e "CREATE DATABASE iot_verify CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"

# 2. Configure required env vars (see docs/getting-started/configuration.md)
export DB_PASSWORD="..." JWT_SECRET="..." IOT_VERIFY_OPENAI_API_KEY="..." NUSMV_PATH="/path/to/NuSMV"

# 3. Backend (http://localhost:8080, auto-creates tables)
cd backend && mvn spring-boot:run

# 4. Frontend (http://localhost:3000)
cd frontend && npm install && npm run dev
```

Full walkthrough: [docs/getting-started/installation.md](docs/getting-started/installation.md).

## Documentation

Start at the **[Documentation Hub / Doc Map](docs/README.md)**. Highlights:

- Getting started: [installation](docs/getting-started/installation.md) ·
  [configuration](docs/getting-started/configuration.md) ·
  [deployment](docs/getting-started/deployment.md)
- API: [conventions](docs/api/overview.md) ·
  [endpoint index](docs/api/rest-endpoints.md) ·
  [auth](docs/api/auth.md) · [board](docs/api/board.md) ·
  [verification](docs/api/verification.md) · [chat (SSE)](docs/api/chat-sse.md) ·
  [counterexample exploration](docs/api/fuzzing.md) ·
  [AI tools](docs/api/ai-tools.md)
- Architecture: [overview](docs/architecture/overview.md) ·
  [device identity](docs/architecture/device-identity.md) ·
  [data authority](docs/architecture/data-authority-model.md) ·
  [verification flow](docs/architecture/verification-flow.md) ·
  [counterexample exploration flow](docs/architecture/fuzzing-flow.md) ·
  [NuSMV model](docs/architecture/nusmv-model.md) ·
  [spec templates & P1–P5](docs/architecture/spec-templates.md) ·
  [auto-fix](docs/architecture/auto-fix.md)
- Frontend integration: [guides/frontend-integration.md](docs/guides/frontend-integration.md)
- Troubleshooting: [guides/troubleshooting.md](docs/guides/troubleshooting.md)
- Changes: [CHANGELOG.md](CHANGELOG.md) · Contributing: [CONTRIBUTING.md](CONTRIBUTING.md)

## License

A license file has not yet been added — the licensing/authorization stance is being
confirmed (see the LICENSE entry in the [Doc Map](docs/README.md)).
