# Known traps

An archive of failures that were diagnosed here at real cost, grouped by what produced them. Every
entry actually happened; none is hypothetical.

The rules distilled from these live in the agent rule files ([CLAUDE.md](../../CLAUDE.md),
[backend/CLAUDE.md](../../backend/CLAUDE.md), [frontend/CLAUDE.md](../../frontend/CLAUDE.md)), which
stay short and link here. This file holds the evidence, so a rule can be trusted without being
re-litigated and a new case can be added without growing a file that loads into every session.

**What they share:** each one reports a failure that reads like a product or compile bug, so the
first instinct — fix the code it points at — makes things worse. Environment and test-authoring
faults are cheap to rule out and expensive to misattribute. Check them first.

Contents:

- [1. Test authoring](#1-test-authoring) — tests that cannot fail, guards that lie
- [2. Build environment](#2-build-environment) — Maven, `target/classes`, the stale dev JVM
- [3. E2E environment](#3-e2e-environment) — rate limits, ports, CORS, worker count, known-flaky specs
- [4. Blast-radius misjudgements](#4-blast-radius-misjudgements) — what reasoning missed

Variable defaults belong to
[getting-started/configuration.md](../getting-started/configuration.md); CSS and layout measurements
belong to [guides/frontend-ui-conventions.md](../guides/frontend-ui-conventions.md) §7. This file
does not restate either — it records what these incidents did to them.

---

## 1. Test authoring

### Tests that cannot fail

A test that passes with the fix reverted proves nothing. Twice, that check revealed the "bug" being
fixed did not exist. One session shipped **five** such tests at once, in the recurring shapes below,
worth recognising by sight. The last two are the inverse failure — a test that cannot *pass* reliably —
and they share the cause: the fixture does not describe what the product or the store actually does.

**The empty scan.** A loop over a selector, directory, or field list that matches nothing. Zero
iterations assert nothing and report success. *Fix:* assert the scan found something *before*
looping — `expect(rules.length).toBeGreaterThan(0)`.

**The wrong slice.** A source-text assertion whose window excludes the place the defect lives.
Slicing `<div` up to a `data-testid` examines only whitespace, so a `v-show` appearing after the
testid is invisible to the assertion. *Fix:* print the slice once and read it.

**The unfalsifiable claim.** Asserting something the framework can never produce.
`doesNotContain("isSourceModelComplete")` passes trivially because Jackson never emits a getter name
as a key; asserting the absence of a symbol that no longer exists anywhere is the same error. *Fix:*
prefer a positive assertion about the value you actually want.

**The unreached path.** A fixture that never enters the branch the test names — a single rule cannot
produce a rule *conflict*, so the helper under test is never called. *Fix:* confirm the mutation
reddens *this* test, not merely some test.

**The impossible fixture.** The mirror of the one above: the fixture reaches a branch the *product*
cannot. An optional prop is the usual door in. `SimulationTimeline`'s `modelSemantics` was declared
`?:` while `SimulationResult.modelSemantics` is required, `requireAttackContext` rejects a response
whose manifest disagrees with its run context, and the bar's only opener refuses to show without a
loaded result — so 17 of its 18 mounts omitted the prop and each rendered the "model semantics
unavailable" warning while claiming to test an ordinary simulation. They passed, because none of them
asserted on the warning. Nothing here reddens under mutation; the test simply describes a different
product than the one that ships, and a later reader takes the fixture for a supported shape. *Fix:*
make the prop required so the type checker enumerates the sites, then assert the impossible state's
*absence* once. Declare a prop optional only when a real union stands behind it — the trace bar keeps
`modelSemantics?` because `TraceEvidence` is shared with the hand-assembled fuzz trace, which carries
no manifest.

**The fixture the store rewrites.** A test asserting on an exact boundary, whose own setup does not
store the value it asserts about. `leaseRecoveryTreatsALeaseExpiringAtTheSampledInstantAsExpired` wrote
`LocalDateTime.now()` as a lease and passed the same value as the sweep cutoff, to pin `lease == now`
as expired. H2 **rounds** a `TIMESTAMP(6)` column to the nearest microsecond and `now()` carries finer
digits, so whenever the sub-microsecond remainder was ≥ 500ns the stored lease landed strictly *after*
the cutoff: the row correctly did not match, and the test failed having never created the equality.
Measured on H2 2.3.232, 91 of 200 raw samples miss it — so it passed twice locally, then failed Fast CI
while Full CI passed **on the same commit**, which is the tell that the fixture and not the product is
at fault. A round number in the failure (`expected: <1> but was: <0>`) on a boundary test is worth one
probe before any other theory. *Fix:* truncate to the precision the column actually keeps, and treat
"same commit, two verdicts" as evidence about the test.

### Guards scoped to a subset

A guard that scans a hand-picked subset lies by omission. One written to catch locale-dependent case
folds scanned six hand-picked packages and missed the highest-stakes fold in the product — the
boot-time check that refuses to start with default secrets.

### Correct by accident

State that was right only because an unrelated refresh happened to repair it. Trace the mechanism; do
not accept the outcome.

---

## 2. Build environment

**When a compile or mass-error failure makes no sense, suspect the build environment once before
believing it.** If it persists, build in an isolated copy — a backend-only copy is *not* sufficient,
because several tests read repo-relative paths and need `../docs/`, the root `README.md`,
`CLAUDE.md`, and `backend/device-template-schema.json`; omitting the schema alone fabricates ~90
failures.

### Deleting or changing an overloaded method needs `mvn clean`

Maven's incremental compile recompiles the changed file against the *previous* `target/classes`, so
overload resolution can bind to a signature that no longer exists in the sources.

Removing two unused list wrappers from `FuzzMapper` — the only remaining `this::toFindingSummaryDto`
/ `this::toFindingDto` method references — made javac resolve a two-arg call to the stale
`FuzzFindingPo` overload instead of the `FuzzFindingSummaryProjection` one. Three compile errors
cascaded into ~100 test errors, a `NoClassDefFoundError` sweep, and a bogus "Mockito cannot mock"
failure. `mvn clean test-compile` on the identical sources succeeds.

The failure looks exactly like a real overload bug, so the instinct is to "fix" the overloads and
break working code. Run `mvn clean` first and re-read the error.

### Restoring a file from a backup copy keeps the mutated class

Undoing a mutation check by copying the backup back (`Copy-Item`, `cp -p`) restores the *old* mtime,
which is older than the class compiled from the mutant, so the incremental compile skips the file and
the tests keep running the mutant. It surfaced as a test that stayed red after the restore, on
sources identical to a green run. Touch the restored file (`(Get-Item f).LastWriteTime = Get-Date`)
or run `mvn clean` before the confirming run.

### Anything else writing to `target/classes` corrupts the run

Two symptoms: a `BUILD FAILURE` whose error list is empty (the tell is *no* `[ERROR] …java:[line]`
entries), and mass `NoClassDefFoundError` / `MockitoException: Could not modify all classes` /
`NoSuchFileException: target\classes\….class` on files javac just wrote.

Two culprits, and the second bites unattended:

- **A second Maven build** in the same checkout. Don't start one while a delegated subagent is
  running the suite, or the result describes neither tree.
- **The VS Code `redhat.java` language server.** Its JDT project for this repo declares
  `kind="output" path="target/classes"` (check the `.classpath` under
  `~/AppData/Roaming/Code/User/workspaceStorage/*/redhat.java/jdt_ws/.metadata/.plugins/org.eclipse.core.resources/.projects/Iot-Verify/`),
  so it auto-builds into the same directory and rewrites class files mid-compile. It is a ~1.3 GB
  `java.exe` that looks like any other JVM in the process list. It produced
  `746 tests, 22 failures, 624 errors` on one attempt and a 21-error `testCompile` failure on the
  next, on a tree whose real result was 2216/2216 green.

### A stale `spring-boot:run` JVM serves the classes it started with

A fix looks ineffective and an already-fixed defect looks live. This cost five wrong hypotheses in
one session. Start the dev backend with its output redirected (`> backend/run.log 2>&1`, already
gitignored) so the next unexplained failure begins with a stack trace.

**`mvn clean` while that JVM is alive is worse than stale.** It keeps loading classes lazily from
`target/classes`, so wiping and rebuilding underneath it leaves it serving a mix of two builds —
restart it before believing anything it says, or a live probe measures neither tree. It can also hold
a lock that fails the `clean` outright (`Failed to delete …/target`, zero tests run, succeeds on a
single retry) intermittently, so one clean run does not disprove it. This is not the `redhat.java`
case above: that one shows mass `NoClassDefFoundError` rather than a failed delete.

**Judge staleness by your modified `.java` mtimes, not `target/classes`.** A full `mvn compile`
rewrites *every* class file, so class timestamps are identical and always newer than the JVM.
Comparing against them reports a stale JVM whenever anything was compiled — a false alarm every time,
and it produced one here: classes read 28 minutes newer than the process, while the only source
edited after startup was a set of `static final` constants whose values matched the literals they
replaced, and a live probe confirmed current behaviour. `git status` names the changed sources.

---

## 3. E2E environment

Four things will make an E2E run lie to you, and all four read as product regressions. **Read the
`reasonCode` or the Playwright trace before believing any E2E failure.**

### Run against a dedicated backend with raised auth caps

**A full pass cannot succeed on the default rate limits.** The suite makes ~67
`createAuthenticatedUser` calls, each registering an account — measurably more than the shipped
`AUTH_SOURCE_REGISTER_RATE_LIMIT_PER_HOUR` allows per hour. An earlier note claimed "a full run stays
under the cap by design" because `sharedReadOnlyAccount` is worker-scoped; measurement says
otherwise, and that false reassurance sent several sessions hunting product defects in a wall of
429s.

**Raising only the register limit is not enough.** `AUTH_SOURCE_LOGIN_RATE_LIMIT_PER_MINUTE` is a
per-source per-minute window, and `board-full-flow.spec.ts` logs in far more often than it registers.
A run with only the register cap raised failed five board specs with `AUTH_LOGIN_RATE_LIMIT_REACHED`
(`scope: SOURCE`) — including the account-cleanup fixture, which then reported a second, misleading
error. Every failure was the limiter, not the product.

The limits are constructor-injected into `final` fields, so **exporting them next to the Playwright
command does nothing** — they must be set on the JVM under test. Do not restart the backend someone
is developing against; start a second one:

```bash
# terminal 1 — a dedicated E2E backend, same database (the suite creates and deletes its own accounts)
cd backend
SERVER_PORT=8081 \
  AUTH_SOURCE_REGISTER_RATE_LIMIT_PER_HOUR=2000 AUTH_REGISTER_RATE_LIMIT_PER_HOUR=2000 \
  AUTH_SOURCE_LOGIN_RATE_LIMIT_PER_MINUTE=2000 AUTH_LOGIN_RATE_LIMIT_PER_MINUTE=2000 \
  mvn spring-boot:run > e2e-backend.log 2>&1

# terminal 2
cd frontend && E2E_API_BASE_URL=http://127.0.0.1:8081 npx playwright test
```

One variable moves the whole run because `vite.config.ts` reads it for the `/api` proxy target as
well. It used to hardcode `localhost:8080` in both `server` and `preview`, so pointing a run
elsewhere *silently half-worked*: the specs' direct API calls followed `E2E_API_BASE_URL` while the
browser kept going to 8080, and one run talked to two servers. Redirect the API and the proxy
together or not at all.

`e2e/global-setup.ts` checks the budget once before any browser starts and prints the four variables
plus the reset time. When the budget exhausts mid-run the failures scatter across the shared fixture
and read exactly like regressions.

### Port 3000 must be free

`reuseExistingServer` is off precisely so a leftover dev server cannot be adopted silently, which
would skip the build and test **stale code** while reporting green. Nothing checks this before the
run, so the symptom is a mid-suite failure that reads like a product bug. Free the port, or set
`E2E_BASE_URL` to a server you manage (inline prefix or exported variable).

**`playwright test --list` is the likeliest source of the leftover.** It starts `webServer` like a real
run but exits without tearing it down, stranding `vite preview --port 3000 --strictPort`. The orphan
keeps its whole parent chain, so it does not look like debris — and because `--strictPort` refuses to
fall back, the *next* run dies in `webServer` startup rather than reporting a port conflict. One
session lost an agent's entire run to an orphan its own earlier `--list` had left. Use `--grep` against
a real run, or `--reporter=list --dry-run` equivalents that do not boot the server, and check the port
afterwards if you must use `--list`.

### Do not edit `playwright.config.ts` while a run is starting

Playwright reads the config once at startup, so a run is self-consistent — but a config edited between
launching a run and its startup produces results you cannot label. Measured: a `workers` value was
changed twice inside 14 seconds while a delegated run was doing its environment checks, which
invalidated the very comparison that run existed to make. If a config change and a verification run
are both wanted, land the change first and let its mtime settle.

### The worker count is pinned because the suite shares one of everything

`playwright.config.ts` sets `workers: 1`. It used to set nothing, which meant a bare local
`npx playwright test` took **half the logical cores** — 14 on a 28-thread machine — against one
backend, one MySQL and one NuSMV, while `.github/scripts/run-e2e.sh` runs the complete suite at
`--workers=1` precisely because that NuSMV budget is shared.

The measured consequence of the unset default: `POST /board/rules/check-duplicate` errored under the
load, which opens the "Check Duplicate — the overlap pre-check failed" confirmation
(`RuleBuilderDialog.vue`). No spec clicks "Save Anyway", so the rule-builder dialog stayed visible and
a 10s `toBeHidden` expired — a failure that reads exactly like a product regression and cost a full
investigation to attribute. It passed when serialized.

CI is unaffected: it passes `--workers` explicitly on every path, and the CLI flag overrides the
config. Use `--workers=2` for a narrow subset, as the risk-routed paths do. Raising it for wall-clock
time is what this default exists to prevent.

### Serving the build yourself: the CORS trap

The port must be one the backend's CORS allowlist knows: 3000, 3001, or 5173–5176 (`CORS_ORIGINS`,
`backend/src/main/resources/application.yaml`). On any other port the register POST returns **403
`Invalid CORS request`**, the UI shows `注册失败` / `登录失败`, and every auth-dependent test fails in a
way that reads exactly like a product regression. One session lost a full run to port 3100 before
reading the response body. This is *not* the rate limiter — check 403-vs-429 first.

### Why a production build, not the dev server

E2E runs against a production build served by `vite preview`. On-demand module transforms made two
parallel browsers exceed the board's load timeout, failing tests that had nothing wrong with them. A
failure that appears only under `--workers=2` is usually this class of cause — diagnose it rather
than forcing `--workers=1`, which hides it and triples the runtime.

Consequence: `vite.config.ts` needs its `/api` proxy declared under `preview` as well as `server` —
`vite preview` does not inherit `server.proxy`.

### Known-flaky specs, with measured causes

CI runs with `--fail-on-flaky-tests`, so one retry-passing test still fails the job.

- **A pointer position computed from the canvas is stale the moment you await.** The edge-label hover
  check measured a hitarea midpoint once and moved the mouse there; under CI load the canvas was
  still settling, the edge had moved, and no label appeared. Re-derive the coordinate *inside* the
  poll and re-hover each attempt rather than widening the timeout around a single stale move.
- **A lingering Element Plus tooltip popper eats the next click, and `force: true` makes it worse.**
  `HintTooltip` teleports its popper to `body` at `z-index: 2009` with an 80ms fade, so clicking a
  board dock button leaves a popper floating over the panel that button just opened — on top of the
  controls inside it. Without `force`, the click retries against an element that "intercepts pointer
  events" and times out, which at least names the cause. **`force: true` does not fix this**: it
  skips Playwright's hit-target *check*, not the interception, so the browser still delivers the
  event to the popper and the call reports success. The test then fails several statements later on a
  control that never appeared, which reads as a product defect.
  Measured from Full CI run 31943156194 (`board-full-flow.spec.ts` fire-evacuation scenario): the
  popper sat at `translate(968px, -476px)` with `inset: auto auto 0 0` — x ≥ 968, y ≤ 244 in a
  1280×720 viewport — and the forced click on `verification-attack-toggle` was delivered at
  (1004, 218), inside it. The switch stayed `aria-checked="false"`, its `v-if`-gated section never
  rendered, and the run died 180s later on `verification-attack-budget`. Use
  `clickUnderTooltip` from `e2e/support/tooltips.ts`, which parks the pointer at the origin, waits
  for the popper to go `aria-hidden`, then clicks with the check intact — and **assert the state the
  click was supposed to produce**, so a swallowed click fails at the click rather than downstream.
  When attributing this class of failure, **sample more than once per build**: single runs read as a
  clean "fails here, passes at HEAD" regression that ten runs contradict (HEAD failed 1 in 19, an
  unrelated feature branch 2 in 9).
- **A route mock must satisfy the same validators as the real response.** `api/chat.ts` validates
  every field it depends on, so a fixture returning a convenient subset is rejected at the boundary —
  and the failure surfaces far from the cause. A session mock missing `active`/`userId`/`updatedAt`
  made session creation throw, so the turn never sent, so a `REFRESH_DATA` command never arrived, and
  the test failed on an unrelated undo-button assertion. When an E2E failure makes no sense, read the
  browser console in the Playwright trace (`--trace=retain-on-failure`) before theorising.
  Mock **every** request the flow makes, not just the ones the assertion needs: a catch-all branch
  answers the rest with the wrong shape, and the product's recovery path becomes part of the test.
  The same spec's `/activity` and `/confirmation` fell through to the session-list answer, so every
  stream settlement failed and raised a warning toast over the header. It passed 29 Full CI runs, then
  run 36566356407 caught the toast still sliding over `board-undo`: the click completed, no undo
  request followed, and the rules never came back.

---

## 4. Blast-radius misjudgements

Two cases where "nothing else can reach this" was wrong. Both now justify an E2E run regardless of
how green the cheaper stages are.

- **A wire-format change.** Narrowing the verify/simulate contract to run parameters passed 2148
  backend and 1040 frontend tests plus a live-backend probe, and still broke three E2E specs — they
  audited scene semantics by reading the request body, which is a contract only a browser-driven run
  observes.
- **A selector rename in a pure restyle.** A dialog restyle updated every unit spec, passed 1231 unit
  tests and `vue-tsc`, and still broke an E2E test that clicked a class the migration had deleted.
  Grep `e2e/` for every selector you rename, and prefer `data-testid` over an appearance class so the
  next restyle cannot reach it.

### Verifying stale artifacts

A rebuilt backend, a cached bundle, or a reused dev server makes the run describe code you are not
editing. Sections [2](#2-build-environment) and [3](#3-e2e-environment) are the two stack-specific
forms of this.
