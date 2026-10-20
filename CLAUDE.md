# CLAUDE.md — IoT-Verify (repo root)

Repo-wide working manual for Claude Code. This file holds **cross-cutting rules and a
map**; stack-specific detail lives in the nearest sub-directory file, which Claude
merges automatically:

- Backend work → also read [backend/CLAUDE.md](backend/CLAUDE.md)
- Frontend work → also read [frontend/CLAUDE.md](frontend/CLAUDE.md)
- Reference docs (authoritative) → [docs/README.md](docs/README.md) (the Doc Map)

Keep this file short and rule-focused. It is a constitution, not documentation — do not
paste reference material here that already lives in `docs/`.

## What this repo is

A verification platform for smart-home IoT systems. Users build a device topology on a
visual canvas, define automation rules and safety specifications, run bounded candidate
counterexample exploration, and use NuSMV for formal conclusions — with formal
counterexample analysis and automatic fix suggestions. Includes an AI assistant (any
OpenAI-compatible LLM endpoint, SSE streaming).

## The prime rule: code is truth, docs stay in sync

**When code and docs disagree, code wins — and you fix the doc in the same change.**
This repo just finished eliminating code/doc drift; do not reintroduce it.

Documentation is never an "afterwards" task. Which doc owns what you changed —
endpoints, DTO fields, config keys, spec templates, fix strategies, AI tools, paper-derived
semantics — is the doc-sync checklist in
[CONTRIBUTING.md](CONTRIBUTING.md#doc-sync-checklist-pr-requirement). Externally visible behaviour
also gets a dated `CHANGELOG.md` entry.

**One fact, one home.** Endpoints live only in `rest-endpoints.md` (index) plus one domain doc;
config defaults only in `configuration.md`; the `Result<T>` envelope, auth and error codes only in
`api/overview.md`. Elsewhere, link — do not restate. If you find the same fact in two docs, one is
wrong: delete the copy and link to the owner.

## Product-first development stage

This project is still in active development. Do **not** preserve a flawed legacy design
only for compatibility with an old implementation. When documentation, interfaces,
business logic, frontend interaction, or modeling semantics do not match the user's
mental model, the project goals, or sound human-computer interaction principles, you may
boldly adjust code, docs, type definitions, tests, and examples. Keep every change
evidence-based, scoped, and verifiable. The primary goal is the user's need and mental
model.

There is no released compatibility contract yet. Unless the user explicitly requires a
migration path, do not add backward-compatibility readers, dual-write formats, rolling-
deployment bridges, deprecated aliases, or silent fallbacks for old development data.
Change all in-repo callers and tests together, version persisted formats when useful, and
reject obsolete or malformed state explicitly. Compatibility code must answer a current,
documented requirement rather than a hypothetical future one.

## No AI slop

AI assistance does not lower the engineering bar. "AI slop" means plausible-looking output that
has not been understood, integrated, or verified, and that pushes hidden review, repair, security,
or maintenance cost onto the next contributor. Judge work by its evidence, not by who typed it.

**The traceability check:** every modified line must map back to something that was asked for — a
requirement, a reproduced defect, or a necessary contract. If it does not, delete it.

- **Read before writing.** Find the owning code, its tests, and the existing helper or domain
  boundary that already covers this. Reuse it rather than cloning logic or inventing a second
  source of truth.
- **Nothing speculative.** No unrequested features, no abstraction for single-use code, no
  configurability nobody asked for, no handling for impossible cases. A new abstraction must
  remove demonstrated duplication or enforce a named invariant — pass-through layers and one-call
  wrappers need a stated reason.
- **Do not leave working notes in the repo.** No summary reports, status files, audit write-ups,
  migration plans, or `FINDINGS.md` — the repo carries product code, its tests, `docs/`, and
  `CHANGELOG.md`, nothing about the process that produced them. Findings belong in your reply to
  the user; durable facts belong in the owning doc. Scratch files, probe scripts, and backups of
  files you are about to edit go outside the checkout (`D:\tmp`), and temporary probe specs are
  deleted before you report the work done.
- **Surgical scope, but pre-existing defects are fair game.** No drive-by reformatting or
  restyling. Fixing something you did not introduce is allowed — and preferred over merely
  reporting it — when you can close the loop: you understand the root cause, the fix is
  evidence-based, and you verify it the same way you would verify your own work. Say what you
  fixed and why in the commit. What still needs asking first is anything the autonomy section
  below reserves (business logic, persisted formats, API contracts, removing behaviour) and
  anything you cannot verify. Match the surrounding style even where you would write it
  differently.
- **Root causes, not symptoms.** Never suppress an error, bypass a validation, or add a
  special-case branch to make a check pass. Never loosen a lint or type config to go green.
- **Never turn an unknown or failed outcome into apparent success.** Reject malformed boundary
  data, preserve typed error semantics, and make partial, stale, cancelled, or unverified states
  explicit to callers and users.
- **Comments carry the "why".** Non-obvious invariants and tradeoffs, not narration, prompt-like
  prose, or claims the code does not support.
- **Before handing off**, read the complete diff as the maintainer who inherits it: account for
  every dependency, public field, helper, and catch block; remove volume; report what you did not
  verify. New production dependencies need explicit justification.

## Autonomy: act, confirm, or refuse

Match caution to reversibility instead of asking about everything or nothing.

**Act without asking** — typo and comment fixes, adding types with no logic change,
behavior-preserving refactors, tests for under-covered code, docs that follow a change you made,
and any decision where the alternatives are equivalent (naming, formatting, default values).

**Confirm first** — changing business logic or a persisted format, adding a production dependency,
altering an API contract or DB schema, removing a feature or a user-visible behavior, and any
scope change beyond what was asked.

**Do not do without an explicit request** — commit or push, push to `main`, touch env vars or
production config, run production migrations, or delete files wholesale (prefer narrowing the
change and saying what you left).

When genuinely blocked, state the assumption you are proceeding under rather than stopping; when
proceeding either way would be unsafe or wasted, ask one specific question.

## Shared conventions

- **Language**: documentation is English ([CONTRIBUTING.md](CONTRIBUTING.md#language-policy) owns the
  policy); code identifiers follow each file's existing style. Chat replies to the user may be in the
  user's language.
- **Encoding**: `.editorconfig` and `.gitattributes` enforce UTF-8 + LF, but **PowerShell can defeat
  them**: prefer `pwsh` (7) and never Windows PowerShell 5.1 `-Encoding utf8`, which writes a BOM.
- **Frontend↔backend contract**: field names are camelCase on both sides
  (e.g. `userId`, not `user_id`). Follow the response conventions in
  [docs/api/overview.md](docs/api/overview.md), including SSE and SMV-download exceptions.
  Keep TypeScript types in `frontend/src/types/` aligned with the backend
  DTOs; document field changes in the owning `docs/api/*.md`.
- **Docs discipline**: no dead links, no stale "(planned)" markers for files that now
  exist, uniform "Verified against code on <date>" notes. Prefer relative Markdown
  links between docs.
- **Match surrounding style**: read neighboring code before writing; mirror its
  conventions, libraries, and comment density rather than introducing new ones.

## Review the source first, then verify

**A green suite is evidence, not a conclusion.** Tests only check what someone thought to check, so
reading the code you changed is the primary review and the suite is the backstop. Before handing
off, read your own complete diff and ask of each change: what breaks if this is wrong, which caller
depends on it, and which state does it now own?

Three failure modes a passing suite hides:

- **Correct by accident.** Trace the mechanism; do not accept the outcome.
- **A test that cannot fail.** Before trusting a new test, break the code it covers and confirm it
  goes red — and that it reddens *this* test, not merely some test. A guard that scans a hand-picked
  subset lies the same way.
- **Verifying stale artifacts.** A rebuilt backend, a cached bundle, or a reused dev server can make
  the run describe code you are not editing.

The recurring shapes, with the incidents behind them:
[docs/development/known-traps.md](docs/development/known-traps.md#1-test-authoring).

When a check fails, find the root cause. "Flaky" is a conclusion that needs evidence — a different
test failing each run points at the environment, the same assertion failing points at the code.

### Scale the check to the change — do not re-run everything per edit

Verification is staged. Running the full suite (or worse, E2E) after every small edit burns minutes
and tokens for information the narrow check already gave you, and it trains you to skim results
instead of reading them.

| Stage | When | What to run |
| :--- | :--- | :--- |
| 1. Narrow | After each edit | The owning spec file(s) plus the mutation check on the behaviour you changed |
| 2. Type | Once per coherent slice of work | `vue-tsc --noEmit` (frontend) / `mvn compile` (backend) |
| 3. Suite | Once a slice is complete, before reporting it done | `npm run test:unit` / `mvn test` — delegate |
| 4. E2E | Once per session-level milestone, or when touching routing, auth, deep links, cross-tab sync, or a real backend contract | `npm run test:e2e` — delegate, never inline |

Rules that follow from this:

- **Stage 1 is where the signal is.** A focused spec run is seconds; use it freely. The mutation
  check (break it, see red, restore) belongs here too — it is per-behaviour, not per-suite.
- **Never run stage 3 or 4 to "see if anything broke" after an isolated edit.** Reason about the
  blast radius instead: if the edit cannot reach a surface, its tests cannot tell you anything new.
- **Batch stages 3 and 4.** Several fixes in one area share one suite run. Finish the area first.
- **E2E is the most expensive check in the repo (minutes, needs MySQL + backend).** It earns its
  cost on integration contracts, not on component-local logic a unit test already pins.
- **Changing a REST request or response shape *is* the E2E trigger, however green the other stages
  are.** Reasoning about blast radius works for logic; it does not substitute for E2E on a wire format.
- **Renaming a class or a test id is the same kind of trigger, even in a pure restyle.** E2E specs
  address the product by selector, so they are the one consumer a "no behaviour changed" argument
  does not cover. Grep `e2e/` for every selector you rename, and prefer `data-testid` over an
  appearance class so the next restyle cannot reach it.
  Both traps, with the runs that proved them:
  [docs/development/known-traps.md](docs/development/known-traps.md#4-blast-radius-misjudgements).
- Re-running a suite that just passed, with nothing changed in between, is never verification.

### Delegate long runs to background subagents

Full test suites, E2E, and live-AI runs take minutes and must not occupy the main thread. Launch
them as background subagents with a read-only, single-purpose brief ("run X, report failures with
root cause, modify nothing"), keep reviewing source in the foreground, and relay results when they
land. Never predict or assume a pending agent's outcome.

The commands themselves live with the stack that owns them — [backend/CLAUDE.md](backend/CLAUDE.md) and
[frontend/CLAUDE.md](frontend/CLAUDE.md) — where their prerequisites and traps are documented alongside
them. Restating them here created a second place to keep in sync for no benefit.

Report results honestly: if a step failed, say so with the output; if you skipped one, say that.

**A long-running dev server serves the code it started with, not the code you just edited — and it
reports success either way.** Both stacks have a version of this, and each is documented with the
stack that owns it: the E2E port and preview proxy in [frontend/CLAUDE.md](frontend/CLAUDE.md), the
`spring-boot:run` JVM and its contention over `target/classes` in
[backend/CLAUDE.md](backend/CLAUDE.md). Suspect it before you suspect the product.

**`git checkout -- <file>` and `git restore <file>` are destructive here.** They discard every uncommitted
change in that file, and in a working session that is all of it. This is not theoretical: undoing a
two-character test mutation that way erased a raw-hue migration, a scroll-region migration, a type-floor pass
and a session of colour-role work from one component, and `git fsck` could not help because nothing had been
staged. To undo a temporary edit, copy the file first and restore from the copy. The danger is that this
command reads as "revert my last thing" while meaning "revert everything since the last commit".

Do not commit or push unless explicitly asked. Direct pushes to `main` are allowed when the user
explicitly requests them, but only after reviewing the complete change set, running the
proportional gates, and checking for secret-bearing files. After the push, follow the CI runs to
completion and address failures before handing off.
Full git/PR conventions: [CONTRIBUTING.md](CONTRIBUTING.md).

## Safety / gotchas that bite across the stack

- **`ProductionSafetyCheck`** refuses to start the backend under a `prod`/`production`
  profile if `JWT_SECRET` / `DB_PASSWORD` / `IOT_VERIFY_OPENAI_API_KEY` hold unsafe defaults.
- **Redis is fail-open**: logout token-revocation degrades silently if Redis is down —
  never make request flow hard-depend on it.
- **NuSMV 2.6–2.7 only** (not nuXmv); the trace parser depends on its English output
  format.
- **Ordinary board mutations are targeted**: add/update/delete only the intended
  device/rule/spec and reconcile from the returned current snapshot. `/api/board/batch`
  is the explicit full-scene replacement command and requires user confirmation in the UI.
  Details in [docs/api/board.md](docs/api/board.md).

## Open decisions (do not resolve silently)

- **LICENSE**: no license file exists; the authorization stance is unconfirmed. Do not
  add a LICENSE or assert a license until the user decides.

## Maintaining these files

This rule set is a living document, not an append-only log. It only works while it keeps changing
behavior, so treat it like code:

- **A rule earns its place by changing a decision.** Prefer a concrete failure mode with its cause
  ("X was silently reused, so the suite went green against stale code") over an adjective ("be
  careful with servers").
- **Prefer enforcement over instruction.** If a rule is mechanically checkable, make it a test, a
  type, or a hook in `.claude/` and keep only a one-line pointer here. If the agent already does
  something correctly without being told, delete the instruction — every unread line makes the
  rules that matter harder to find.
- **Prune when you add.** Merge overlapping rules, delete guidance the code now enforces, and delete
  anything describing structure that no longer exists. A rule nobody reads protects nothing.
- **Detail lives in `docs/`, not here.** These files are an index of cross-cutting constraints; link
  rather than restate.
- **Stale is worse than missing.** When code and this file disagree, code wins — fix the file in the
  same change.

Layout: this root file plus `backend/CLAUDE.md` and `frontend/CLAUDE.md` hold stack-specific
guidance. The root `AGENTS.md` is a Codex mirror of *this* file — keep its body identical, differing
only in the header/footer, and do not add backend/frontend AGENTS files.

Each rule above carries its own rationale inline, which is where the "why" belongs — a separate change log
of this file duplicated those reasons and `git log -p CLAUDE.md` already holds the history. Product and
behaviour changes go in [CHANGELOG.md](CHANGELOG.md); that is its job, not this file's.
