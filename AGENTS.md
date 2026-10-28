# AGENTS.md

## Agent roles and delegation

This section describes both agents. Apply only the instructions for your role;
the project conventions below apply to both. Keep this section, `CLAUDE.md`,
and `docs/agent-workflow.md` consistent when changing the workflow.

- Codex is the **manager**: investigate requests, define scope and acceptance criteria, delegate implementation to the installed Claude Code CLI, review diffs, verify results, and report to the user.
- Claude Code is the **developer** for implementation tasks; see `CLAUDE.md` for its task-specific instructions and review-only behavior. These roles are unrelated to the application's `manager/` modules.

### Codex only

- For implementation tasks, Codex must read `docs/agent-workflow.md` and delegate to Claude Code. Do not substitute a Codex sub-agent for Claude. Codex may directly maintain coordination instructions and task briefs; application code, tests, and feature documentation are Claude's responsibility unless the user explicitly changes the division of work.
- Codex must inspect the working tree before delegation, preserve existing changes, and give Claude a concrete brief with scope, acceptance criteria, checks, and protected files. Run only one developer in a checkout; do not edit its assigned files while it is running.
- Review actual diffs and test evidence rather than accepting the developer's summary alone. Return concrete findings to Claude for correction and review again before declaring completion.
- Codex owns completion of required verification. Claude runs checks permitted by its current tool permissions; if a check is denied, Codex runs that check through its own execution tools after Claude finishes. Do not repeatedly delegate a denied check without a permission change. Report any checks neither agent could run.
- If Claude is unavailable, unauthenticated, or blocked from implementation by permissions, report the exact blocker; do not silently take over implementation or bypass permission checks. User authorization to perform a task does not automatically grant Claude CLI tool permissions; use each execution tool's approval mechanism when required.
- No automatic commit, push, merge, or deployment is implied by this workflow.

## Project overview

TS抜き録画データを処理・管理するためのアプリケーション ([README.md](README.md)). Two independently-deployed pieces:

- `backend/` — Kotlin/Spring Boot, Gradle multi-module ([backend/README.md](backend/README.md))
- `frontend/` — Next.js/TypeScript, talks to `manager:api` over HTTP ([frontend/README.md](frontend/README.md))

Domain terms (録画, 番組, 分割ファイル, 作成ファイル, タグ etc.) are defined in [docs/ubiquitous-language.md](docs/ubiquitous-language.md); keep it in sync when adding or renaming domain concepts. Where each kind of doc lives (`docs/` cross-cutting, `backend/docs/` per-feature ops/design, module READMEs) is described in [docs/README.md](docs/README.md); add new docs to the matching index.

## Commands

### Backend (run from `backend/`)

```bash
./gradlew build                              # build all modules
./gradlew test                               # run all tests (needs Docker; Testcontainers spins up MariaDB)
./gradlew catalog:test --tests "me.pinfort.tsvideos.catalog.SomeSpec"   # run a single test class
./gradlew ktlintCheck                        # lint
./gradlew ktlintFormat                       # auto-fix lint
./gradlew manager:api:bootRun                # run the API locally (needs docker-compose.test.yml services)
./gradlew processor:console:bootRun --args="process <path-or-dir>"   # run the processing pipeline CLI (tvpcli); pass -d/--dry-run to skip writes
./gradlew processor:console:bootRun --args="after-encode"             # run the Amatsukaze post-encode step (reads ITEM_ID/IN_PATH/FILES/SUCCESS/ERROR_MESSAGE)
./gradlew processor:console:bootRun --args="reset <recorded-file>"     # undo processing for one recording: delete its program + every generated file (keeps the original); prompts to confirm, -d/--dry-run supported
./gradlew processor:console:bootRun --args="release-claim <recorded-file>"   # after a crash (all processors stopped): release that recording's abandoned upload claims; prompts to confirm, -d/--dry-run lists only
./gradlew processor:console:bootRun --args="status --job-id <id>"           # read-only: current job state and latest/active attempts (history for the same: `history`); --json for machine output
./gradlew processor:console:bootRun --args="chapters backfill --video-store-root <dir>"   # backfill mp4 chapters through a local/UNC video-store root; -d/--dry-run probes without writing
./gradlew processor:console:bootRun --args="quality report --job-id <id>"   # read-only: retained quality-rule reports (--json); `quality release --job-id <id>` returns a quarantined job to RETRYABLE after confirmation (--dry-run/--yes)
```

Local DB/NAS dependencies: `docker compose -f docker-compose.test.yml up -d` from the repo root (see [backend/README.md](backend/README.md) for env var overrides).

### Frontend (run from `frontend/`)

```bash
pnpm install
pnpm dev                    # dev server
pnpm build                  # production build
pnpm typecheck               # tsc --noEmit
pnpm lint                   # eslint
pnpm test                   # vitest run (all)
pnpm test:watch              # vitest watch mode
pnpm test -- path/to/file.test.tsx   # run a single test file
pnpm coverage                # vitest run --coverage
```

`NEXT_PUBLIC_API_BASE_URL` controls the backend URL, defaulting to `http://localhost:8080`.

## Architecture

The system architecture — components, module responsibilities and dependency direction, public/internal boundaries, the `process`/`after-encode` flow and its rollback boundaries, operational commands, persistence, frontend, configuration/logging/shutdown/retries, test strategy and the #139/#165/#166 restructuring — is described in [docs/architecture.md](docs/architecture.md) (Japanese, with Mermaid diagrams). Binding module/table/transaction contracts are in [backend/docs/module-boundaries.md](backend/docs/module-boundaries.md); feature behavior is in [backend/docs/](backend/docs/README.md) and the module READMEs. Read the relevant ones before changing a module. The rules below are the conventions to follow when editing code.

### Backend conventions

- Allowed production project dependencies (enforced by `checkModuleGraph` in `backend/module-graph.gradle.kts`, the per-app runtime-isolation checks and each module's `check<Module>Boundaries`, all part of `check`):
  - `storage`, `database-schema`, `console-common`: no project dependencies. `database-schema` is referenced only as test fixtures from DB tests (`testImplementation(testFixtures(project(":database-schema")))`).
  - `catalog` → `storage`; `diagnostics` → `storage`; `library` → `catalog`, `storage`; `processing` → `catalog`, `storage`, `diagnostics` (+ `me.pinfort:tsselect`).
  - `manager:api` → `storage`, `catalog`, `library`, `diagnostics`; `manager:console` → the same + `console-common`; `processor:console` → `storage`, `catalog`, `diagnostics`, `processing`, `console-common`.
  - The manager apps never depend on, scan, register mappers of or include the profile of `processing`, and never get `me.pinfort:tsselect` at runtime. `processor:console` never gets `library`. The removed modules `core`, `manager:infrastructure` and `processor:infrastructure` must not come back. When adding a module or edge, update `ModuleGraphRules` and `module-boundaries.md` §2 together.
- Callers use only a module's public packages: `storage.api`; `catalog.api`/`catalog.domain`; `library.api`/`library.domain`; `diagnostics.api`/`diagnostics.domain`; `processing.api` (incl. `api.config`/`api.shell`/`api.tool`)/`processing.domain`. `persistence` packages, `processing.pipeline`, `processing.external.*` (tsselect, TS detection, Slack) and the Samba implementation are internal. Constructors taking internal types stay `internal`; inject them as Spring beans outside the module.
- Ledger business logic belongs in `catalog`, low-level NAS operations in `storage`. `library` and `processing` use catalog public operations, not ledger mappers/DTOs. Collection/tag filtering for the API is `library`'s `LibraryProgramSearchCommand`; catalog's `ProgramCommand.search` accepts ledger conditions only.
- `manager:api` is a thin Spring MVC layer (`controller`/`response`/`exception`) over catalog and library commands; keep business logic out of controllers. `manager:console` and `processor:console` are Clikt CLIs (`commands/`) over the same public operations. Put things in `console-common` only if both CLIs actually use them.
- Business modules never define a DataSource, transaction manager, `SqlSessionFactory` or `Logger`. Each executable app owns `config/LoggerConfig` (prototype-scoped, per-injection-point slf4j `Logger`), `application-datasource.yaml` (`datasource` profile; keep it identical across the three apps) and a `runtimeOnly` MariaDB driver, and registers mappers through `CatalogPersistence`/`LibraryPersistence`/`ProcessingPersistence.MAPPER_PACKAGE`. Only `processor:console` scans `me.pinfort.tsvideos.processing`, adds `me.pinfort.tsvideos.processing.api.config` to `@ConfigurationPropertiesScan` and includes the `processing` profile.
- Processing safety rules: `FileProcessingPipeline` rolls back only the stages before the upload starts; once an upload starts nothing is rolled back so evidence survives for reconciliation. `AfterEncodeRunner` never rolls back (log, Slack, program `ERROR`; a completed result is kept — late/duplicate callbacks for a `COMPLETED` job, and history or job-completion failures after the program became `COMPLETED`, leave it `COMPLETED`). Every NAS upload goes through `VerifiedUploadCommand`; delete a local file only after its upload is REGISTERED and re-verified. Mutating `tvpcli` subcommands accept `-d`/`--dry-run`. Cancellation goes through `ProcessingCancellation` (interrupted attempts are recorded, not rolled back); transient-failure retries go only through `ProcessingRetry`.
- Detector results are stored as tags, not columns: add constants to `catalog/domain/ExecutedFileTag`/`ExecutedFileCheck` and call `ExecutedFileTagCommand.recordCheck` — no schema change. Keep "not detected" and "not checked" distinguishable. Detector tags are unrelated to the operator-managed manual collections/tags (`library_collection`/`library_tag`, `libraryTagId`).
- MyBatis is used directly (mapper interfaces + XML/annotations under each module's `persistence`), not JPA/Hibernate.
- Tests use Kotest (not JUnit assertions) + MockK (not Mockito — explicitly excluded in `build.gradle.kts`) + Testcontainers-backed MariaDB for anything touching the DB. The schema's single source of truth is the numbered SQL files in `database-schema/src/main/resources/db/migrations/`; the Testcontainers init script (`ddl/01_create_database.sql`) is generated from them by `database-schema:generateTestDdl`, and `docker-compose.test.yml` mounts them too (add a mount line for each new migration). Test seed data is `database-schema/src/testFixtures/resources/ddl/02_insert_data.sql`. Production DDL is in [backend/README.md](backend/README.md).

### Frontend conventions

- Next.js App Router; pages under `frontend/app/**`, shared UI under `frontend/components/`.
- All backend calls go through `frontend/lib/api/` (`client.ts` has the shared `fetchJson`/`ApiError` helpers; the other files wrap specific endpoints). Add new backend calls there rather than calling `fetch` directly from components/pages.
- The video playback/download page fetches video bytes directly from the backend in the browser (not proxied through Next.js), so `NEXT_PUBLIC_API_BASE_URL` must be a browser-reachable URL.
- Every component/page/lib module has a co-located `*.test.tsx`/`*.test.ts` file (Vitest + Testing Library + jsdom, configured in `vitest.config.ts`). Follow that co-location pattern for new files.
