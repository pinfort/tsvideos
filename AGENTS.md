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
./gradlew core:test --tests "me.pinfort.tsvideos.core.SomeSpec"   # run a single test class
./gradlew ktlintCheck                        # lint
./gradlew ktlintFormat                       # auto-fix lint
./gradlew manager:api:bootRun                # run the API locally (needs docker-compose.test.yml services)
./gradlew processor:console:bootRun --args="process <path-or-dir>"   # run the processing pipeline CLI (tvpcli); pass -d/--dry-run to skip writes
./gradlew processor:console:bootRun --args="after-encode"             # run the Amatsukaze post-encode step (reads ITEM_ID/IN_PATH/FILES/SUCCESS/ERROR_MESSAGE)
./gradlew processor:console:bootRun --args="reset <recorded-file>"     # undo processing for one recording: delete its program + every generated file (keeps the original); prompts to confirm, -d/--dry-run supported
./gradlew processor:console:bootRun --args="release-claim <recorded-file>"   # after a crash (all processors stopped): release that recording's abandoned upload claims; prompts to confirm, -d/--dry-run lists only
./gradlew processor:console:bootRun --args="status --job-id <id>"           # read-only: current job state and latest/active attempts (history for the same: `history`); --json for machine output
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

### Backend module graph

- `database-schema` — independent SQL resources module; owns numbered migrations, maintenance SQL, and generated DDL/seed shared through test fixtures. No application runtime dependency or data access API.
- `storage` — no internal module dependencies; owns NAS public contracts (`storage/api`), internal Samba implementation, NAS settings, and SHA-256 helpers. Other modules use only `storage.api`.
- `catalog` — depends on `storage`; owns ledger models, public operations (`catalog/api`, `catalog/domain`) and internal MyBatis persistence. `checkCatalogBoundaries` rejects reverse dependencies and external access to internals.
- `diagnostics` — depends on `storage`; owns DB/NAS environment diagnostics and public results, with constructor-based composition that works without a Spring context. Processing tool checks live in processing.
- `library` — depends on `catalog` + `storage`; owns management search, statistics/storage overview, manual classification, duplicate review, bulk operations, cleanup, health scans and verification. Only `library.api`/`library.domain` are public; MyBatis persistence is internal.
- `processing` — depends on `catalog` + `storage` + `diagnostics` (+ the `me.pinfort:tsselect` library); owns the processing pipeline, reset and after-encode (public entry points `PathProcessingRunner`/`AfterEncodeRunner`/`ResetRunner` in `processing.api`, since #166), tsselect drop check, TS emergency-broadcast/superimpose detection, Slack notification, processing-job ownership, history, recording quality, retry, cancellation, verified uploads (`VerifiedUploadCommand`/`UploadManifest`/`UploadManifestMapper`, since #165), preflight, external tool/shell wrappers, processing helpers and the `processor-tool.*`/`processor-preflight.*` settings (`application-processing.yaml`, `processing` profile). Only `processing.api` (incl. `api.config`/`api.shell`/`api.tool`) and `processing.domain` are public; MyBatis persistence is internal. `checkProcessingBoundaries` rejects dependencies on core/library/manager/processor and external access to internals ([backend/processing/README.md](backend/processing/README.md)).
- `core` — no internal module dependencies; retains only `ApplicationVersion`/`LoggerConfig`/DataSource settings (until #139). The verified upload moved to processing in #165, removing the transitional core → processing dependency.
- `console-common` — no internal deps; Clikt/terminal I/O components shared by both consoles (`UserQuestionComponent`, `TerminalTextColorComponent`, package `me.pinfort.tsvideos.console.common`). Only put things here that both `manager:console` and `processor:console` actually use
- `manager:infrastructure` — depends on `core`
- `manager:api` — depends on `storage` + `catalog` + `diagnostics` + `library` + `core` + `manager:infrastructure`; never `processing` (`checkProcessingRuntimeIsolation` rejects it on the resolved runtime classpath)
- `manager:console` — depends on `storage` + `catalog` + `diagnostics` + `library` + `core` + `console-common` + `manager:infrastructure`; never `processing` (same check)
- `processor:console` — depends on `storage` + `catalog` + `diagnostics` + `processing` + `core` + `console-common` (the former `processor:infrastructure` was merged into `processing` in #166)

- **`catalog`** owns the ledger models, recording/program/split/created-file and detector-tag operations, path helpers, and internal Mapper/DTO classes. Callers use `catalog.api` and `catalog.domain` only. `CreatedFileCatalog` provides reads and caller-transaction registration/metadata updates; `ProgramInventory`, `LedgerSnapshots`, `LedgerLocks` and `CatalogStatistics` provide purpose-specific reads and MANDATORY locks. Applications register its mappers through `CatalogPersistence.MAPPER_PACKAGE` using the same DataSource, SqlSessionFactory and transaction manager as core.
- **`processing`** owns `ProcessingJobCommand`/`ProcessingJobClaimCommand`, `ProcessingHistoryCommand`, `RecordingQualityCommand`/`RecordingQualityInspector`, `ProcessingRetry`, `ProcessingCancellation`, `ProcessingPreflightCommand`, the TsSplitter/Amatsukaze/ffprobe clients, shell wrappers and pipeline helpers. Constructors taking its internal mappers are `internal`; applications register its mappers through `ProcessingPersistence.MAPPER_PACKAGE`, scan `me.pinfort.tsvideos.processing`, add `me.pinfort.tsvideos.processing.api.config` to `@ConfigurationPropertiesScan` and include the `processing` profile. Only `processor:console` does so; the manager applications neither depend on, scan nor configure processing (since #165).
- **`core`** retains only `ApplicationVersion`/`LoggerConfig`/DataSource settings until #139. Ledger business logic belongs in catalog; low-level NAS operations belong in storage. library's `LibraryProgramSearchCommand` owns API collection/tag filtering, while catalog's `ProgramCommand.search` accepts ledger conditions only. Library and processing consumers use catalog public operations rather than ledger mappers/DTOs.
- **`manager:api`** is a thin Spring MVC layer (`controller`/`response`/`exception`) over catalog and library commands — controllers should stay free of business logic. `BulkLibraryController` exposes `library`'s `BulkLibraryCommand` (preview → fingerprinted apply of bulk delete/move; see [backend/docs/bulk-library.md](backend/docs/bulk-library.md)). `LibraryHealthController` exposes `library`'s `LibraryHealthCommand` (saved state, read-only) and `LibraryHealthScanCommand` (an explicit, single-flight NAS verification persisted by `HealthVerificationStore`; see [backend/docs/library-health.md](backend/docs/library-health.md)).
- **`manager:console`** is a Clikt-based CLI (`commands/`) over the same catalog and library commands, for operator scripts (search/get/modify/delete, plus `duplicates`, which lists programs recorded from the same broadcast via `library`'s `DuplicateProgramCommand`, and `stats`, read-only library totals grouped by channel/month via `library`'s `LibraryStatsCommand`, and `doctor`, which validates DB/NAS settings and probes connectivity (optionally NAS write access with `--check-write`) via `diagnostics`' `EnvironmentDiagnosticCommand`; it bootstraps through `DoctorBootstrap` without a Spring context so broken configuration stays diagnosable, and `verify`, a read-only NAS existence/size check of registered `created_file` artifacts via `library`'s `LibraryVerificationCommand` and storage's `NasFileVerifier`, reporting VERIFIED/MISSING/SIZE_MISMATCH/INACCESSIBLE/UNCHECKED and exiting 4 when anything is not VERIFIED, and `cleanup plan`/`cleanup apply`, which write a reviewed JSON cleanup plan (duplicates or a retention cutoff) via `library`'s `CleanupPlanCommand` and later delete it through `BulkLibraryCommand`'s guarded apply, re-checking the whole plan per target under locks).
- **`processing`'s pipeline** (moved from the former `processor:infrastructure` in #166) turns raw recordings into the state `manager` can track. Its entry points `PathProcessingRunner`, `AfterEncodeRunner` and `ResetRunner` are public in `processing.api`; the pipeline internals (`processing.pipeline`), tsselect/TS detection (`processing.external.tsselect`/`external.ts`) and Slack (`processing.external.slack`) are internal. `pipeline/FileProcessingPipeline` is a 4-stage pipeline (drop check → TsSplitter → compress + upload to NAS → Amatsukaze add-task); each stage up to compression rolls back itself and every earlier stage in reverse order before rethrowing on failure; once the upload starts nothing is rolled back, so registration/split/upload evidence survives for reconciliation. The drop-check stage uses `external/tsselect/TsSelectClient` (internal to processing), which counts dropped frames in-JVM via the `me.pinfort:tsselect` library's `tsDump` mode — it replaced the old Windows-only `tsDropChkx64.exe` shell-out (`DropChkClient`, removed). The same stage then runs `external/ts/EmergencyBroadcastDetector`, a second in-JVM pass that parses PAT/PMT and the superimpose PES (ARIB caption data via `AribCaptionData`) and records the result as tags via `catalog`'s `ExecutedFileTagCommand` (see below). `api/PathProcessingRunner` expands a file/directory into `.m2ts` files and runs the pipeline per file, reporting failures to Slack via `external/slack/SlackClient` (`SLACK_WEBHOOK_URL`, no-op when unset). `api/AfterEncodeRunner` is the tail of the same flow, called once Amatsukaze finishes an encode: it uploads the encoded outputs to the video-store NAS, registers them as `created_file` rows, deletes the local outputs and originals and completes the program. Both the compressed original and encoded outputs go through `processing`'s `VerifiedUploadCommand`: a durable `upload_manifest` row (claim token + state machine NEW → CREATING → TRANSFERRING → TEMP_VERIFIED → FINALIZING → FINALIZED → REGISTERED) records ownership before each NAS effect, uploads go to an exclusively created temporary file that is verified and renamed without replacement, and a local file is deleted only after its upload is REGISTERED and its SHA-256 re-checked (see `backend/docs/verified-uploads.md`). `upload`/`register`/`verifyRegistered` retry typed transient network/DB-connection failures through `processing`'s `ProcessingRetry` (`processor-retry.*`), only while the manifest is unclaimed and in a safe state (see `backend/docs/processing-retries.md`). Unlike `FileProcessingPipeline` it does **not** roll back — a failure is logged, sent to Slack and leaves the program in `ERROR`.
- **`processor:console`** is a Clikt CLI (`tvpcli`, `commands/TsVideosProcessor`) with seven subcommands: `process` (`commands/ProcessCommand` over `PathProcessingRunner`) takes one or more paths, `after-encode` (`commands/AfterEncodeCommand` over `AfterEncodeRunner`) reads Amatsukaze's post-encode environment variables, `reset` (`commands/ResetCommand` over `processing`'s `ResetRunner`) takes one recorded-file path and undoes its processing — resolves `executed_file` → `program`, then deletes every `splitted_file`/`created_file` row, the NAS copies and the local split files, and finally the `program` and `executed_file` rows (the original recording is kept), and `release-claim` (`commands/ReleaseClaimCommand` over `VerifiedUploadCommand.claimed`/`releaseAbandonedClaim`) clears upload-manifest claims a crashed process left behind, after confirmation and without touching state or NAS. `status` and `history` (`commands/HistoryCommand` over `processing`'s `ProcessingHistoryCommand`) are read-only views of the per-job attempt/stage history that `process`/`after-encode` record (`--job-id`/`--all`, `--json`; see `backend/docs/processing-history.md`). `quality` (`commands/QualityCommand` over `processing`'s `RecordingQualityCommand`) has `report` and `release` for the opt-in recording quality rules: `process` evaluates them through `processing`'s `RecordingQualityInspector` after inspection and before registration, and a QUARANTINE decision holds the original in place (job QUARANTINED, exit 4; see `backend/docs/recording-quality.md`). `reset` prompts for confirmation via `component/UserQuestionComponent` (a port of the Python `reset.py`/`RemoveProgram`; no rollback). All mutating subcommands accept `-d`/`--dry-run`; `process`/`after-encode` render drop-check/compress/upload progress bars via `display/ProgressPrinter`. Runs as a non-web Spring Boot app (`CommandLineRunner`). On SIGINT/SIGTERM, `ConsoleRunner`/`ProcessorShutdown` ask `processing`'s `ProcessingCancellation` (one owner per invocation) to stop. Work then halts at the next check, the attempt is recorded INTERRUPTED without rollback, and the CLI exits 130 (native signal status such as 143 may surface instead) within `PROCESSOR_SHUTDOWN_GRACE_SECONDS` (see `backend/docs/graceful-shutdown.md`).
- Detector results are stored as **tags**, not columns: `executed_file_tag` holds the tags a detector attached (names in `catalog/domain/ExecutedFileTag`) and `executed_file_check` records which detectors ran (`ExecutedFileCheck`), so "not detected" and "not checked" stay distinguishable (`ProgramDetail.detected(tag)`). Add a new detector by adding constants and calling `ExecutedFileTagCommand.recordCheck` — no schema change. `ExecutedFileCommand.delete` also removes the file's tags/checks. These detector tags are unrelated to the operator-managed **manual collections/tags** (`library_collection`/`library_tag`, `LibraryOrganizationCommand`, `libraryTagId`), which attach to `program` via FK-cascading association tables ([backend/docs/library-organization.md](backend/docs/library-organization.md)). Production DDL is in [backend/README.md](backend/README.md).
- MyBatis is used directly (mapper interfaces + XML/annotations under `catalog/persistence`, `library/persistence` and `processing/persistence`), not JPA/Hibernate.
- Tests use Kotest (not JUnit assertions) + MockK (not Mockito — explicitly excluded in `build.gradle.kts`) + Testcontainers-backed MariaDB for anything touching the DB. The schema's single source of truth is the numbered SQL files in `database-schema/src/main/resources/db/migrations/`; the Testcontainers init script (`ddl/01_create_database.sql`) is generated from them by `database-schema:generateTestDdl`, and `docker-compose.test.yml` mounts them too (add a mount line for each new migration). Test seed data is `database-schema/src/testFixtures/resources/ddl/02_insert_data.sql`.

### Frontend

- Next.js App Router; pages under `frontend/app/**`, shared UI under `frontend/components/`.
- All backend calls go through `frontend/lib/api/` (`client.ts` has the shared `fetchJson`/`ApiError` helpers; `programs.ts`/`video.ts` wrap specific endpoints). Add new backend calls there rather than calling `fetch` directly from components/pages.
- The video playback/download page fetches video bytes directly from the backend in the browser (not proxied through Next.js), so `NEXT_PUBLIC_API_BASE_URL` must be a browser-reachable URL.
- Every component/page/lib module has a co-located `*.test.tsx`/`*.test.ts` file (Vitest + Testing Library + jsdom, configured in `vitest.config.ts`). Follow that co-location pattern for new files.
