# IoT-Verify Frontend

Vue 3 + TypeScript + Vite single-page app for the IoT-Verify platform. This file covers
running the frontend locally and its source layout. For everything else, see the project
**[Documentation Hub](../docs/README.md)**.

## Run locally

Prerequisites: Node.js 20.19+ on Node 20, or Node.js 22.12+. The backend should be running on `http://localhost:8080`
(see [../backend/README.md](../backend/README.md)).

```bash
npm install
npm run dev        # http://localhost:3000, proxies /api → http://localhost:8080
```

Other scripts: `npm run build` (type-check via `vue-tsc` + production build),
`npm run preview`, `npm run test:unit` (Vitest), `npm run test:e2e` (Playwright).
For E2E startup, backend prerequisites, and auth-rate-limit headroom, follow
[CLAUDE.md](CLAUDE.md#commands).

## Source layout

See the [frontend layout](../docs/architecture/overview.md#frontend-layout).

## How the frontend talks to the backend

See [docs/guides/frontend-integration.md](../docs/guides/frontend-integration.md) for
the axios client, the real `boardApi` / `authApi` shapes, the two response-unwrapping
conventions, and the SSE chat streaming. Backend API contracts:
[docs/api/](../docs/api/rest-endpoints.md).

API origin configuration: [configuration reference](../docs/getting-started/configuration.md#frontend-vite).

## License

See the LICENSE note in the project [README](../README.md#license).
