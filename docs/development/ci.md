# CI pipeline

Two E2E tiers selected by affected code, plus a separate live-AI gate.

Verified against code on 2026-10-30 for exclusive tier execution and build reuse. Sources: `.github/workflows/`,
`.github/ci-risk-router.mjs`, `.github/check-ci-results.mjs`, `.github/scripts/run-e2e.sh`, `frontend/playwright.config.ts`,
`frontend/scripts/run-e2e.mjs`, and `frontend/e2e/live-ai-no-mock.spec.ts`.

## Why it is split

Fast CI is the push/PR entry point. Ordinary source changes select scoped unit tests,
fresh builds and a real-backend smoke subset. Main and high-risk changes select reusable
Full CI instead: both unit suites, builds and the complete E2E suite run once. Smoke is
part of the full suite and is not run separately on that path.
The [risk router](../../.github/ci-risk-router.mjs) owns the path rules.

## Tier 1 — Fast CI (`fast-ci.yml`)

Runs on every push and pull request; the router selects which jobs execute.

1. **Route by risk** — computes the changed paths and asks [`ci-risk-router.mjs`](../../.github/ci-risk-router.mjs)
   which tiers to run. Runs the router's own tests first, so a broken router fails loudly instead of
   silently routing everything to the cheap tier. It also validates documentation cross-references
   ([`docs-link-check-cli.mjs`](../../.github/docs-link-check-cli.mjs)), because a documentation-only
   branch change routes as inert and skips every tier below — making this the only job that reads those files.
   Both checks need no dependency install and add about a second.
2. **Frontend** — `npm ci`, project typecheck, affected frontend unit tests, Vite build.
   The project check includes `vite.config.ts`; the later Vite command does not repeat it.
3. **Backend** — `mvn package`, with NuSMV and a dedicated MySQL test database when backend
   tests are selected. A frontend-only change packages the unchanged backend with tests skipped.
4. **E2E smoke** — a browser-driven barrier against real MySQL, Redis, and the packaged jar.

Any smoke run requires both fresh artifact producers, even when only one language's unit
suite is selected. A backend-only change still builds the frontend; a frontend-only change
still packages the backend. Missing or failed producers block smoke rather than trying to
download an artifact that no job created. Full-selected routes skip these Fast jobs entirely.

The smoke subset is tagged **per test** with `@smoke`, selected by
[`playwright.config.ts`](../../frontend/playwright.config.ts), and used by both local and CI runners.
The initial 2026-10-29 discovery found 330 cases: 312 ordinary cases and 18 live-AI cases.
After retiring cosmetic tours and redundant matrix combinations, the ordinary suite has
263 cases; the 18 live-AI cases remain separate. Smoke is included in the ordinary suite.
The former four-file smoke selection had grown to 61 cases; it now selects 10 critical cases
(seven browser journeys and three HTTP integration checks):

| Spec | Protects |
| :--- | :--- |
| `error-contract` | REST error envelopes |
| `board-recovery` | Failed layout-read recovery |
| `canvas-runtime-environment` | One shared environment value across readers |
| `ui-contracts` | Login returns to a private deep link |
| `theme-layout` | Real UI registration reaches the workspace |
| `cross-tab-board-sync` | A persisted edit reaches another browser tab |
| `household-spec-edit` | Requirement editing, undo and redo preserve state |
| `smv-model-download` | Real verification delivers the checked model |

These are **selection counts**, not a measured wall-clock speedup. Smoke should grow only for a new cross-layer risk, not for
another viewport, translation, colour, or error-message spelling.
The verification/download journey asserts a complete response with five conclusive property
results as well as valid downloaded model bytes. A visible dialog or generated file alone does
not prove that verification ran successfully.

### Retired coverage and retained responsibilities

The broad `editor-copy`, `global-copy-review` and `panel-opening-tooltips` tours were removed
(12 cases). They repeatedly provisioned real accounts/scenes to check wording, exact button
heights, header gaps, or unsolicited close hints. Form validation and localized feedback belong
to the component tests; shared dialog rules belong to the style contracts. `ui-contracts`
retains actual browser checks for loading-strip placement, tooltip dismissal and focus recovery.
Exact per-dialog pixel/copy sweeps are intentionally no longer E2E obligations.

The four workspace-typography tours were also retired: each visited editors, recommendations,
chat and account settings, then waited for two export toasts to disappear just to assert exact
font sizes, weights, padding and radii. Readability floors, focus indicators, target sizes and
toast pointer transparency retain their source-contract checks; representative layout and
keyboard journeys remain in the browser suite. Registration validation stays in `Landing.spec.ts`,
and the duplicate dark registration-card check is covered by the shared auth layout's login checks
and the real registration journey. Single-heading assertions now share existing login/404 journeys.

Thirty repeated matrix cases were also retired. Layout checks keep representative desktop,
narrow and short viewports, with real dark-theme and Chinese-text coverage. Composer resizing
still covers mouse/keyboard interaction on narrow Chinese and wide English layouts.
Each parameter/condition repair strategy runs once, and one full keyboard repair journey
retains cancellation, confirmation, undo, redo and re-verification. Import integration keeps
JSON, UTF-8 CSV and GB18030 lifecycle coverage; parser/encoding component tests cover input
combinations without repeating the whole persisted workflow for each UI language.

A new browser case must identify a browser-only or cross-layer failure it detects. A second
language or viewport should require a specific layout or interaction risk; it is not an
automatic multiplier for an existing persistence/solver case.

The backend retains the original 41-device Salus house and 23-device dynamic-home repair
regressions, including every successive repair, protected response and initial-transient check.
Their two enlarged variants only cloned existing lights, rules and specification conditions;
they repeated the same assertions with 61 and 39 devices and were retired with the unused
expansion helper. The original scenarios continue to exercise changing inputs and repair
preservation. A future scale benchmark should have an explicit performance requirement.

## Choose the smallest useful check

| Change or assertion | Appropriate check |
| :--- | :--- |
| Domain parsing, formula semantics, serialization | Owning unit tests and backend model tests |
| Component state, validation messages, request payloads | Vue component tests |
| CSS/source conventions | Node source-contract tests; these do not prove rendered behaviour |
| Layout geometry, focus, pointer interaction, multiple tabs | Targeted browser tests; jsdom cannot prove these |
| Login, persistence, verification, download across layers | Tagged smoke journeys |
| Viewport/theme/language matrices and broad scene/repair audits | Affected E2E cases or Full CI |
| External provider/tool behaviour | Explicit live-AI suite |

The 32 source-only style test files declare the Node environment. The two style tests that
actually use the DOM retain jsdom. Assertions and coverage are retained while avoiding a DOM
environment for text parsing. The auth-budget advice tests were removed: they asserted diagnostic
prose, environment-variable spellings and a hardcoded registration estimate rather than harness
behaviour. Actual auth fixture failures still include the backend response and setup advice.

During local iteration, run affected files first. Avoid rerunning the whole frontend, building
production assets, or starting the full browser stack after every wording or metadata edit.
Broaden verification for changed contracts, unresolved failures, or a release decision.
`test:changed` follows Vitest's import graph. Tests that read source files through `fs` are not
ordinary import dependencies; run `test:contracts` explicitly for CSS/template convention changes.

`npm run test:e2e` defaults to smoke, with no automatic retries and a one-failure stop.
Full runs stop after five final failures; use `--max-failures=0` explicitly when every failure
is needed. CI retains its explicit retry and flaky-test policy. Failed or interrupted cases
are not a passing complete regression run.
The explicit live-AI runner enables `RUN_LIVE_AI_E2E=1`; it requires a backend configured
for an external provider. This avoids a green run made entirely of disabled live-AI cases.
Listing remains offline and never calls the provider.

The local runner probes an unauthenticated board read before building the frontend. An
unreachable backend or a response other than 401 stops immediately. CI uses the same read
for readiness. The former registration-budget probe was removed: it spent rate-limit budget,
could create an account, and depended on an obsolete fixed registration count. Actual 429
registration failures now report the backend diagnostics and rate-limit setup advice.

Normal runs write `frontend/test-results/e2e-results.json` with per-case timings. CI uploads
this alongside its existing diagnostics. Use those timings and actual assertions when deciding
what to split or retire; screenshot capture alone is not a visual-regression assertion.
Viewport-changing recommendation checks wait for the Board's responsive layout state before
opening a panel, so the resize handler cannot collapse it between the opener and the next click.

## Tier 2 — Full CI (`full-ci.yml`)

The complete E2E suite, excluding live AI, runs against real infrastructure:

- **`main`** — called by Fast CI for every landed commit, including documentation changes.
- **Nightly (03:17 UTC)** — the only thing that catches decay in code nobody touched.
- **Manual dispatch** — release candidates, or re-checking a suspicious branch.
- **High-risk branch pushes and pull requests** — called by Fast CI after routing.

Full CI has no standalone push trigger. Keeping both that trigger and the reusable call
would run the same commit twice. Its frontend and backend jobs run in parallel; the full
browser job waits for both and downloads their current-workflow artifacts. Live-AI runs
remain in their separate workflow; Full CI has no live-AI dispatch toggle.

The Fast and Full Maven test jobs provision `iot_verify_undo_it`, separate from the E2E application database,
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
- **Fail-fast ordering**: route → the selected tier's checks/builds → browser tests.
  Frontend project typechecking runs before units and bundling. The local `npm run build`
  command still includes typechecking; CI splits those stages so it runs once.
- **Artifact reuse**: Fast and Full pass the current workflow's backend jar and frontend
  `dist` to their browser job. CI sets `E2E_PREBUILT_DIST=1`; Playwright starts only the
  preview server and refuses a missing `dist/index.html` instead of rebuilding or falling
  back to an older bundle. Local runs still build by default. Offline `--list` needs no
  bundle or server. The preview process retains `E2E_API_BASE_URL` for its API proxy.
- **Shared startup**: [`run-e2e.sh`](../../.github/scripts/run-e2e.sh) owns the readiness probe and
  process lifecycle for all three tiers. Duplicated inline in YAML, a subtly different probe in one job
  reports "never became ready" for a backend that was fine.
- **Aggregate status check**: `fast-ci` waits for route, frontend, backend, smoke and
  reusable Full CI. Every selected job must succeed, every unselected job must be skipped,
  and missing or malformed routing outputs fail. Cancellation and an unexpected skip
  cannot be reported as a successful required tier.
- **Actions are pinned to a commit SHA with the version in a trailing comment**, so a moved tag cannot
  change what runs. The pin *shape* is asserted for every `uses:` line in `.github/workflows/` **and**
  every `.github/actions/*/action.yml` — scanning only the workflows once left the `setup-nusmv`
  composite action unguarded through a version bump. What no test can check is whether the comment names
  the SHA's actual tag: one bump left `# v4.2.0` beside a v4.3.0 SHA. When bumping, resolve the tag with
  `gh api repos/<owner>/<action>/git/ref/tags/<tag> --jq .object.sha` and update both halves together.
  Local JavaScript composite actions are checked for `node24` by the router tests.

## Branch protection

Branch protection is a GitHub repository setting; workflow files do not establish
which checks are required. The aggregate `Fast CI` job includes the reusable Full CI
result on main and high-risk routes. Its success establishes that the selected validation
completed. Nightly and manual Full CI runs remain independently visible.

## Local equivalents

```bash
node --test .github/ci-risk-router.test.mjs   # routing logic
node --test .github/check-ci-results.test.mjs .github/ci-workflows.test.mjs # execution/gate contracts
node --test .github/docs-link-check.test.mjs  # the docs checker's own logic
node .github/docs-link-check-cli.mjs .        # documentation links and anchors
cd backend  && mvn test                       # backend suite
cd frontend && npm run test:unit -- --run     # frontend unit
cd frontend && npm run test:changed           # affected frontend tests during iteration
cd frontend && npm run test:contracts         # CSS/template contracts, including their DOM cases
cd frontend && npm run test:e2e               # 10 critical E2E cases (needs the test backend)
cd frontend && npm run test:e2e:full          # all non-live E2E, explicitly requested
cd frontend && npm run test:e2e:live          # external-provider cases only
```

For one regression, restrict the full runner to its owning file or title:

```bash
npm run test:unit -- --run src/utils/__tests__/spec.spec.ts
npm run test:e2e:full -- e2e/household-spec-edit.spec.ts
npm run test:e2e -- --list                    # offline discovery; no backend or browser starts
```

To see how a change would route:

```bash
CHANGED_PATHS="$(git diff --name-only origin/main)" node .github/ci-risk-router.mjs
```
