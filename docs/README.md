# IoT-Verify Documentation

This map assigns each topic to one document. Keep facts in their owner document and
link there from other pages. Documentation is English; see the
[language policy](../CONTRIBUTING.md#language-policy). Code takes precedence over docs.

Verified against code on 2026-10-27 for document paths and ownership. Individual
verification notes describe each document's code sources; development guides describe
process and diagnosed incidents.

## Doc Map

### Root-level

| Document | Owns |
| :--- | :--- |
| [README.md](../README.md) | Project pitch, feature highlights, 5-minute quick start, links into `docs/` |
| [CHANGELOG.md](../CHANGELOG.md) | Change history (`Unreleased` + dated entries until a release process exists) |
| [CONTRIBUTING.md](../CONTRIBUTING.md) | Branch/commit conventions, **doc-sync discipline**, language policy |
| `LICENSE` (pending) | Authorization decision; see [README](../README.md#license). |

### Getting started

| Document | Owns |
| :--- | :--- |
| [docs/getting-started/installation.md](getting-started/installation.md) | Full prerequisites & install (JDK/Node/MySQL/Redis/NuSMV) |
| [docs/getting-started/configuration.md](getting-started/configuration.md) | **SSOT** for every environment variable and default value |
| [docs/getting-started/deployment.md](getting-started/deployment.md) | Production packaging, Nginx, profiles, `ProductionSafetyCheck` |

### Architecture

| Document | Owns |
| :--- | :--- |
| [docs/architecture/overview.md](architecture/overview.md) | System topology, front/back boundary, package layout |
| [docs/architecture/device-identity.md](architecture/device-identity.md) | Canonical device identity, display-label boundaries, NuSMV `varName` normalization |
| [docs/architecture/data-authority-model.md](architecture/data-authority-model.md) | Field-level data ownership for devices, environment pool, rules, specs, traces, tasks, and fix |
| [docs/architecture/verification-flow.md](architecture/verification-flow.md) | SmvGenerator → NusmvExecutor → SmvTraceParser pipeline |
| [docs/architecture/fuzzing-flow.md](architecture/fuzzing-flow.md) | HAFuzz-inspired bounded exploration role, pipeline, supported finite semantics, and proof boundary |
| [docs/architecture/nusmv-model.md](architecture/nusmv-model.md) | SMV modeling logic, identifier sanitization, user-input → model mapping |
| [docs/architecture/spec-templates.md](architecture/spec-templates.md) | 7 spec templates ↔ CTL/LTL, `templateId` mapping, P1–P5 |
| [docs/architecture/auto-fix.md](architecture/auto-fix.md) | Fault localization + parameter/condition/rule-removal strategies + forward verification |
| [docs/architecture/shared-value-semantics.md](architecture/shared-value-semantics.md) | Shared-value identity, domains, capabilities, evolution, composition, conflicts, and provenance |
| [docs/architecture/theory-sources.md](architecture/theory-sources.md) | Which published algorithm owns which modeling/fix/exploration behaviour, with section citations |

### Development

CI and diagnosed development failures.

| Document | Owns |
| :--- | :--- |
| [docs/development/ci.md](development/ci.md) | The two-tier CI design: what Fast CI and Full CI each cover, which changes count as high risk and why, caching keys, and the live-AI gate |
| [docs/development/known-traps.md](development/known-traps.md) | Diagnosed test, build, E2E, and blast-radius failures |

### API

| Document | Owns |
| :--- | :--- |
| [docs/api/overview.md](api/overview.md) | `Result<T>` envelope, auth convention, error codes |
| [docs/api/rest-endpoints.md](api/rest-endpoints.md) | **Index only**: method, path, controller, one-line note, link to domain doc |
| [docs/api/auth.md](api/auth.md) | Auth DTO-level contract |
| [docs/api/board.md](api/board.md) | Board/rules/specs/templates/recommend contracts |
| [docs/api/verification.md](api/verification.md) | Verify/simulate/task/trace/fix DTO-level contract |
| [docs/api/fuzzing.md](api/fuzzing.md) | Counterexample-exploration task/run/finding DTO-level contract |
| [docs/api/chat-sse.md](api/chat-sse.md) | SSE streaming protocol for chat |
| [docs/api/ai-tools.md](api/ai-tools.md) | AI tools: names, categories, argument semantics |

### Guides

**Conventions and integration** — binding rules and how-it-works, read while writing code:

| Document | Owns |
| :--- | :--- |
| [docs/guides/frontend-integration.md](guides/frontend-integration.md) | How the frontend calls the backend (axios/SSE, real `boardApi`/`authApi` shape, type locations) |
| [docs/guides/frontend-ui-conventions.md](guides/frontend-ui-conventions.md) | Board UI rules: URLs, feedback, undo, dialogs, actions, colours, typography, depth, and replay |
| [docs/guides/troubleshooting.md](guides/troubleshooting.md) | FAQ (MySQL/Redis/CORS/NuSMV/timeout) |

**Scenes and walkthroughs** — scripted runs of the product, for acceptance and demonstration:

| Document | Owns |
| :--- | :--- |
| [docs/guides/household-workflow.md](guides/household-workflow.md) | Household intent, editable boundaries, choice of analysis, and the explain–revise–recheck workflow |
| [docs/guides/acceptance-demo.md](guides/acceptance-demo.md) | Night-watch diagnostics: three construction paths, observation and counterexample replay, attack/privacy contrast, and rejection of vacuous repair |
| [docs/guides/default-template-scenarios.md](guides/default-template-scenarios.md) | **Scene semantics SSOT** for additional importable default-template and elderly-care scenes (fire response, rule priority, RFID trust/privacy, away-mode unlock, care response timing, ventilation, attack contrast, verified repair), including each scene's expected verification counts |
| [docs/guides/away-mode-unlock-demo.md](guides/away-mode-unlock-demo.md) | Presenter walkthrough for the away-mode unlock scene: composition defect, three-state counterexample, two strategies declining with stated reasons, verified removal, optional budget-one attack act, and on-stage failure modes |

### Module READMEs & Claude manuals

| Document | Notes |
| :--- | :--- |
| [backend/README.md](../backend/README.md) | Slimmed to local-run + package layout + links into `docs/` |
| [frontend/README.md](../frontend/README.md) | Slimmed to local-run + source layout + links into `docs/` |
| [CLAUDE.md](../CLAUDE.md), [AGENTS.md](../AGENTS.md), [backend/CLAUDE.md](../backend/CLAUDE.md), [frontend/CLAUDE.md](../frontend/CLAUDE.md) | Root manuals share cross-cutting rules; stack manuals own backend/frontend guidance |

## Assets

### Presentation and demo

| Asset | Use |
| :--- | :--- |
| [PowerPoint presentation](assets/IoT-Verify-presentation.pptx) | 21 slides with embedded media and native animations. Open in PowerPoint Slide Show mode to use the animation sequence. |
| [PDF handout](assets/IoT-Verify-presentation.pdf) | Static version of the presentation. |
| [Demo video](assets/IoT-Verify-demo.mp4) | 176.4-second, 1080p demonstration with Chinese Vivian narration. |
| [Chinese subtitles](assets/IoT-Verify-demo.zh-CN.srt) | Subtitle track for the demo video. |
| [Narration audio](assets/IoT-Verify-narration.wav) | Standalone Chinese narration used by the video. |

The video records a fixed scene and search run from 2026-10-27. Current exploration
semantics and limitations are documented in [fuzzing-flow.md](architecture/fuzzing-flow.md).

### Earlier project documents

- `docs/assets/智链未来企划书.docx` — project proposal (binary; see `.gitattributes`).
- `docs/assets/智护安居.pdf` — project document (binary; see `.gitattributes`).

Archived showcase files retain their original names and language; they do not define
current product contracts.

## Ownership rules (how duplication is prevented)

1. **Endpoints**: `api/rest-endpoints.md` owns the method/path/controller index; one domain API doc owns DTO fields, examples, and validation.
2. **Configuration**: `getting-started/configuration.md` owns runtime variables and defaults.
3. **Global API conventions**: `api/overview.md` owns `Result<T>`, authentication, and error codes.
4. **Shared values**: `architecture/shared-value-semantics.md` owns semantics; `nusmv-model.md` owns emitted SMV; `data-authority-model.md` owns field authority.
5. **Published algorithms**: `architecture/theory-sources.md` owns paper/section attribution.
6. **AI tools**: `api/ai-tools.md` owns the catalog and arguments.
7. **Default scenes**: `guides/default-template-scenarios.md` owns scene semantics and expected counts; walkthroughs link there.
8. **History**: `CHANGELOG.md` owns dated changes. Fold durable findings into the owning doc; keep audit reports out of the repo.

## Documentation checks

Run the owning test after editing these contracts.

| Document | Test | What it enforces |
| :--- | :--- | :--- |
| [api/verification.md](api/verification.md) | `ModelSnapshotDocumentationTest` | Every field of the run-snapshot and provenance DTOs appears in the field tables — a new DTO field fails until documented |
| [api/ai-tools.md](api/ai-tools.md), [architecture/overview.md](architecture/overview.md) | `AiToolCatalogDocumentationTest` | The stated tool count matches the concrete `*Tool` classes (also checked in the root and module READMEs and `backend/CLAUDE.md`) |
| [architecture/theory-sources.md](architecture/theory-sources.md) | `TheorySourceConformanceTest` | The documented paper-derived semantics match what the code implements |
| [architecture/shared-value-semantics.md](architecture/shared-value-semantics.md) | `EnvironmentProvenanceCollectorTest` | Provenance behaviour matches the documented semantics |
| [guides/away-mode-unlock-demo.md](guides/away-mode-unlock-demo.md) | `AwayModeUnlockSceneNusmvTest` | The walkthrough's counterexample claims hold under a real NuSMV run |
| [api/auth.md](api/auth.md) | `AccountDeletionCoverageTest` | The documented account-deletion coverage matches the code |

`backend/CLAUDE.md` is pinned the same way by `SchemaDocumentationTruthTest` (table count, table
names, composite keys). A doc test should pin *facts*, never phrasing — if one blocks a legitimate
rewrite, loosen the test rather than contorting the prose, and verify by mutation that it still
catches the defect it exists for.
