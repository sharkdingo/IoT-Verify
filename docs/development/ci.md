# CI pipeline

Two E2E tiers selected by affected code, plus a separate live-AI gate.

Verified against code on 2026-10-21. Sources: `.github/workflows/`,
`.github/ci-risk-router.mjs`, `.github/scripts/run-e2e.sh`, `frontend/playwright.config.ts`,
and `frontend/e2e/live-ai-no-mock.spec.ts`.

## Why it is split

Fast CI runs the build, unit tests, and a real-backend smoke subset. Full CI adds the
complete E2E suite where model, security, persistence, or shared-contract changes warrant it.
The [risk router](../../.github/ci-risk-router.mjs) owns the path rules.

## Tier 1 — Fast CI (`fast-ci.yml`)

Runs on every push and pull request; the router selects which jobs execute.

1. **Route by risk** — computes the changed paths and asks [`ci-risk-router.mjs`](../../.github/ci-risk-router.mjs)
   which tiers to run. Runs the router's own tests first, so a broken router fails loudly instead of
   silently routing everything to the cheap tier. It also validates documentation cross-references
   ([`docs-link-check-cli.mjs`](../../.github/docs-link-check-cli.mjs)), because a documentation-only
   change routes as inert and skips every tier below — making this the only job that reads those files.
   Both checks need no dependency install and add about a second.
2. **Frontend** — `npm ci`, typecheck, unit tests, build. Typecheck runs before tests because it is
   the cheapest check that catches the largest class of mistake.
3. **Backend** — `mvn package` (tests plus the jar), with NuSMV and a dedicated MySQL test database.
4. **E2E smoke** — a browser-driven barrier against real MySQL, Redis, and the packaged jar.

The smoke subset is selected in [run-e2e.sh](../../.github/scripts/run-e2e.sh):

| Spec | Protects |
| :--- | :--- |
| `error-contract` | REST error envelopes |
| `board-recovery` | Recovery after failed operations |
| `canvas-runtime-environment` | Canvas and shared environment values |
| `ui-contracts` | Cross-page UI contracts |

## Tier 2 — Full CI (`full-ci.yml`)

The complete E2E suite, excluding live AI, runs against real infrastructure:

- **`main`** — what gets released.
- **Nightly (03:17 UTC)** — the only thing that catches decay in code nobody touched.
- **Manual dispatch** — release candidates, or re-checking a suspicious branch.
- **High-risk branch pushes** — escalated automatically by the router.

Both Maven jobs provision `iot_verify_undo_it`, separate from the E2E application database,
and pass `-Diot.verify.mysql.required=true`. The MySQL integration tests cover
fresh-schema constraints, edit undo, task leases, cancellation, Board captures, history queries
and transaction ownership, and verification-result projections. An unavailable or invalid connection
fails CI instead of silently skipping them. Local runs retain optional MySQL admission; use the same flag when that coverage
is required. The workflow tests guard the database provisioning and required-test flag.

The full browser suite has a 30-minute global deadline inside a 40-minute E2E job, leaving time
for infrastructure setup and diagnostic uploads. The expanded serial suite exceeded the former
15-minute deadline while still making progress, leaving tests unexecuted. Individual test and
assertion deadlines remain in force, and `--fail-on-flaky-tests` still rejects retry-only success.

## What counts as high risk

Not diff size. A one-line change in any of these gets the full suite, because a wrong edit here can
produce a wrong *verdict*, leak data, corrupt persisted state, or break a contract another layer
already trusts:

| area | why |
| :--- | :--- |
| `component/nusmv/`, `component/fuzz/` | a wrong model produces confident false verdicts |
| `device-template-schema.json`, `deviceTemplate/` | the authoring contract and bundled semantics |
| `security/`, `filter/`, `*Auth*`, `*Jwt*`, `*RateLimit*` | authentication, authorization, rate limiting |
| `db/`, `po/`, `repository/` | migrations and persistence mapping |
| `dto/`, `controller/` | cross-layer REST contracts |
| `frontend/src/{stores,router,api}/` | cross-cutting frontend state |
| `frontend/src/utils/{modelRequest,device,modelSemantics}.ts` | shared model contract |
| `.github/`, `playwright.config`, `e2e/`, `pom.xml`, `package-lock.json` | the pipeline and its harness |

**Unrecognised paths escalate.** A new top-level directory has unbounded blast radius, so failing
safe costs a slow pipeline while failing open ships something nothing validated.

To add a rule, state *why* in the same commit. The test suite asserts every reason is non-trivial so
the list stays a standard rather than folklore.

## Tier 3 — Live AI (`live-ai-ci.yml`)

Tests that call a real external model endpoint have their own gate: nightly at 04:23 UTC
(after Full CI, so a shared infrastructure failure shows up in the cheaper workflow first) and on
demand, never a merge blocker. Three reasons:

- Provider availability can fail a run independently of the commit.
- It costs real quota per run.
- It needs a secret, unavailable to fork pull requests.

It is the only tier without `--fail-on-flaky-tests`, for the same reason: a transport failure is not a
defect in this repository.

**Without the secret the run is skipped, not failed.** A `preflight` job reads
`IOT_VERIFY_OPENAI_API_KEY` and publishes a boolean the real job gates on — the `secrets` context is
unavailable in a job-level `if:`, so a secret cannot gate a job directly. A missing key produces a
warning annotation and a job summary stating that the AI path was **not** verified, then skips; red
stays reserved for an actual break in that path.

To enable this tier, set `IOT_VERIFY_OPENAI_API_KEY` and, for a compatible endpoint,
`IOT_VERIFY_OPENAI_BASE_URL` as repository secrets.

**A skipped job still makes the run report `success`**, which is why a third `status` job exists. It
depends on both others with `if: always()` and renames itself to `Live AI NOT verified (no API key)`
when the gate was skipped, so the check list and the run page carry the state that the run's own
conclusion cannot. It fails only for a real break: a suite failure, or a `preflight` that did not
succeed — an unknown credential state is not a skip. The one configuration error it does report red is a
key containing whitespace, because that is unusable in an HTTP header and would otherwise fail ~10
minutes later at the model call; unlike an absent key, it is unambiguous and actionable. The preflight
also reports whether `IOT_VERIFY_OPENAI_BASE_URL` is set, since it cannot know whether this key belongs
to `api.openai.com` or to a compatible endpoint — that line is what makes a later auth or 404 failure
diagnosable.

The three invariants this rests on — the suite gates on the preflight boolean, the paid job carries its
own repository check rather than inheriting it through `needs:`, and the `status` job announces an
unverified run — are asserted in `.github/ci-risk-router.test.mjs`, because dropping any of them turns a
paid gate into a permanent silent skip that nothing else notices.

The rule-recommendation subset in `live-ai-no-mock.spec.ts` makes twelve real model requests:
API signals, numeric conditions, enum conditions, working states, modes, and content-label
transfer, each in English and Simplified Chinese. Each case uses a disposable account, checks
the validated candidate and its language, opens its details and rationale without changing the
board, explicitly adds the rule, and checks that undo restores the complete scene. It covers
the ordinary add action with an empty starting rule set; similarity-review confirmation is a
separate flow.

To run only this subset against existing application servers, run from `frontend/` with
`RUN_LIVE_AI_E2E=1`, `E2E_BASE_URL` set to the frontend URL, and `E2E_API_BASE_URL` set to the
backend URL:

```bash
npx playwright test e2e/live-ai-no-mock.spec.ts --grep 'bilingual rule flow' --workers=1 --retries=0
```

The external provider must be configured on the backend; see
[AI configuration](../getting-started/configuration.md). Keep the same backend process running
through the subset and correlate the attached request/user ids with the recommendation
validation and worker-failure logs described in [troubleshooting](../guides/troubleshooting.md#ai-recommendation-failed).

## Caching

| cached | key derived from | why it is safe |
| :--- | :--- | :--- |
| Maven repository | `pom.xml` (via `setup-java`) | immutable released artifacts |
| npm modules | `package-lock.json` (via `setup-node`) | lockfile pins exact versions |
| Playwright browsers | `frontend/package-lock.json` + OS | a lockfile change invalidates the browser cache |
| NuSMV 2.7.1 | version + sha256 + OS + arch | fixed URL and verified digest; runtime libraries are installed separately |

**Deliberately not cached:** the MySQL and Redis service containers, the compiled backend jar across
runs, build outputs, and apt packages. Caching mutable application state or generated results is how a
pipeline starts hiding the defect it exists to catch. The jar *is* passed between jobs within one run
as an artifact, which is reuse of a verified build rather than a stale one.

## Other decisions

- **Concurrency cancellation** on non-`main` refs. A superseded push is wasted capacity and leaves a
  misleading red X on an old commit. `main` never cancels: every landed commit needs its own verdict.
- **Fail-fast ordering**: route → typecheck → unit → build → smoke. Each stage is cheaper than the one
  it gates.
- **Artifact reuse**: each workflow passes its tested backend jar to its E2E job.
  Playwright builds the frontend before starting the preview server.
- **Shared startup**: [`run-e2e.sh`](../../.github/scripts/run-e2e.sh) owns the readiness probe and
  process lifecycle for all three tiers. Duplicated inline in YAML, a subtly different probe in one job
  reports "never became ready" for a backend that was fine.
- **Aggregate status check**: `fast-ci` collects route, frontend, backend, and smoke
  results; the escalated Full CI result remains separate.
- **Actions are pinned to a commit SHA with the version in a trailing comment**, so a moved tag cannot
  change what runs. The pin *shape* is asserted for every `uses:` line in `.github/workflows/` **and**
  every `.github/actions/*/action.yml` — scanning only the workflows once left the `setup-nusmv`
  composite action unguarded through a version bump. What no test can check is whether the comment names
  the SHA's actual tag: one bump left `# v4.2.0` beside a v4.3.0 SHA. When bumping, resolve the tag with
  `gh api repos/<owner>/<action>/git/ref/tags/<tag> --jq .object.sha` and update both halves together.
  Local JavaScript composite actions are checked for `node24` by the router tests.

## Branch protection

Branch protection is a GitHub repository setting; workflow files do not establish
which checks are required. The aggregate `Fast CI` job depends on route, frontend,
backend, and smoke jobs. It does **not** depend on `escalate`, so its success alone
does not establish that the high-risk Full CI run passed. Check that result separately
before merging a high-risk change.

## Local equivalents

```bash
node --test .github/ci-risk-router.test.mjs   # routing logic
node --test .github/docs-link-check.test.mjs  # the docs checker's own logic
node .github/docs-link-check-cli.mjs .        # documentation links and anchors
cd backend  && mvn test                       # backend suite
cd frontend && npm run test:unit -- --run     # frontend unit
cd frontend && npm run test:e2e               # full E2E (needs MySQL + Redis + NuSMV)
```

To see how a change would route:

```bash
CHANGED_PATHS="$(git diff --name-only origin/main)" node .github/ci-risk-router.mjs
```
