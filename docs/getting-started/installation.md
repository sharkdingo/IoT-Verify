# Installation

> Verified against code on 2026-09-30. Source: root `README.md`, `backend/README.md`,
> `backend/pom.xml`, `frontend/package.json`, `frontend/.npmrc`, `frontend/vite.config.ts`,
> `frontend/playwright.config.ts`.

This guide covers a local development setup of the IoT-Verify platform (Vue 3 frontend + Spring Boot backend + NuSMV model checker). For the complete list of environment variables and their defaults, see [configuration.md](./configuration.md) — it is the single source of truth. This page only names the variables you must set.

## Prerequisites

| Dependency | Version | Notes |
| ---------- | ------- | ----- |
| JDK        | 17+     | Backend runtime (Spring Boot 3.5.7 targets Java 17) |
| Node.js    | 20.19+ on Node 20, or 22.12+ | Frontend runtime; enforced by `frontend/package.json` and `frontend/.npmrc` |
| Maven      | 3.6+    | Backend build. Not pinned by a manifest — there is no Maven wrapper, so this is the tested floor, not an enforced one |
| MySQL      | 8.0+    | Primary datastore. Not pinned by a manifest; the schema is created by Hibernate `ddl-auto: update` |
| Redis      | 6.0+    | Optional, and not pinned by a manifest. Logout revocation and cross-instance operation coordination; failures fall back to documented process-local behavior |
| NuSMV      | 2.6-2.7 | Formal verification engine. **NOT nuXmv — nuXmv is incompatible.** |

### NuSMV

Download NuSMV from the [NuSMV official site](http://nusmv.fbk.eu/). You need a 2.6 or 2.7 release; nuXmv will not work.

Typical install paths (used to set `NUSMV_PATH`):

- Windows: the executable under `bin/NuSMV.exe` inside the extracted release directory — the shipped
  default assumes a specific one, so check [configuration.md](./configuration.md) before relying on it
- Linux: `/usr/local/bin/NuSMV`
- macOS: `/usr/local/bin/NuSMV`

Verify the install resolves:

```bash
NuSMV -version   # should report 2.6 or 2.7 (not nuXmv)
```

### AI features

AI-powered assistance (chat, rule/spec/device recommendations) requires an API key for an OpenAI-compatible endpoint (the official OpenAI API or a relay). Set it via `IOT_VERIFY_OPENAI_API_KEY`, and point `IOT_VERIFY_OPENAI_BASE_URL` at your endpoint (see [configuration.md](./configuration.md)).

## Steps

### 1. Create the MySQL database

Create a database named `iot_verify` with the `utf8mb4` character set:

```bash
mysql -u root -p -e "CREATE DATABASE iot_verify CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
```

The backend auto-creates all tables on first startup, so no schema import is needed.
Keep the schema-level `utf8mb4_unicode_ci` default so fresh installations retain the same
non-username text comparison semantics as upgraded databases. On every MySQL startup, the
backend separately verifies `app_user.username` and migrates that column only to the
accent- and case-sensitive `utf8mb4_0900_as_cs` collation. This keeps normalized usernames
deterministic without silently changing identifiers and literal domain names elsewhere.
Back up an existing database before the first startup with this version and allow time for
the column-alter lock; startup fails rather than serving with an unverified username
collation.

### 2. Set required environment variables

All backend settings can be overridden by environment variables. At minimum you must set the following (full list and defaults in [configuration.md](./configuration.md)):

- `DB_PASSWORD` — your MySQL password
- `JWT_SECRET` — signing key for JWT auth (min 256 bits)
- `IOT_VERIFY_OPENAI_API_KEY` — required for AI features
- `IOT_VERIFY_OPENAI_BASE_URL` — OpenAI-compatible endpoint (defaults to the official API; set for a relay)
- `IOT_VERIFY_OPENAI_MODEL` — model id or deployment name sent to the endpoint
- `NUSMV_PATH` — absolute path to the NuSMV executable (required for verification)

```bash
export DB_PASSWORD="your_mysql_password"
export JWT_SECRET="your-secret-key-here-min-256-bits"
export IOT_VERIFY_OPENAI_API_KEY="your-api-key"
export IOT_VERIFY_OPENAI_BASE_URL="https://your-relay.example/v1"   # omit to use the default
export IOT_VERIFY_OPENAI_MODEL="your-model-id"                      # omit to use the default
export NUSMV_PATH="/path/to/NuSMV"
```

The two commented lines are only needed to override a default; the defaults themselves are in
[configuration.md](./configuration.md).

### 3. Build and run the backend

```bash
cd backend
mvn clean install -DskipTests
mvn spring-boot:run
```

The backend listens on `http://localhost:8080` and creates its tables automatically on first run.

### 4. Install and run the frontend

```bash
cd frontend
npm install
npm run dev
```

The dev server listens on `http://127.0.0.1:3000` and proxies `/api` to the backend.
Scripts and E2E prerequisites: [frontend README](../../frontend/README.md#run-locally).

For the frontend source layout see [../architecture/overview.md](../architecture/overview.md#frontend-layout); for how the frontend calls the backend see [../guides/frontend-integration.md](../guides/frontend-integration.md).

## Verify the install

Register a user, then log in. Both endpoints are documented in [auth.md](../api/auth.md).

```bash
# Register
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"phone":"13800138000","password":"password123","username":"testuser"}'

# Login
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"identifier":"testuser","password":"password123"}'
```

Responses follow the [API conventions](../api/overview.md). If either call fails,
see [troubleshooting](../guides/troubleshooting.md).

## Next steps

- Production setup: [deployment.md](./deployment.md)
- Configuration reference: [configuration.md](./configuration.md)
- API overview: [../api/overview.md](../api/overview.md)
- Documentation map: [../README.md](../README.md)
