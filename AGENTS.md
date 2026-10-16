# AGENTS.md

## Project overview

TS抜き録画データを処理・管理するためのアプリケーション ([README.md](README.md)). Two independently-deployed pieces:

- `backend/` — Kotlin/Spring Boot, Gradle multi-module ([backend/README.md](backend/README.md))
- `frontend/` — Next.js/TypeScript, talks to `manager:api` over HTTP ([frontend/README.md](frontend/README.md))

Domain terms (録画, 番組, 分割ファイル, 作成ファイル, タグ etc.) are defined in [docs/ubiquitous-language.md](docs/ubiquitous-language.md); keep it in sync when adding or renaming domain concepts.

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

- `core` — no internal deps; everything else depends on it
- `console-common` — no internal deps; Clikt/terminal I/O components shared by both consoles (`UserQuestionComponent`, `TerminalTextColorComponent`, package `me.pinfort.tsvideos.console.common`). Only put things here that both `manager:console` and `processor:console` actually use
- `manager:infrastructure` — depends on `core`
- `manager:api` — depends on `core` + `manager:infrastructure`
- `manager:console` — depends on `core` + `console-common` + `manager:infrastructure`
- `processor:infrastructure` — depends on `core`
- `processor:console` — depends on `core` + `console-common` + `processor:infrastructure`

- **`core`** owns everything shared: domain models (`core/domain`), MyBatis DTOs/mappers for DB access (`core/external/database`, where each DTO converts itself to its domain model via `toDomain()`), the Samba/NAS client (`core/external/samba`), shell execution (`core/external/shell`), wrappers around external recording tools (`core/external/tool` — `TsSplitterClient`, `AmatsukazeAddTaskClient`, `DurationProbeClient`), reusable pipeline helpers (`core/component` — e.g. `CompressComponent`, `DirectoryNameComponent`, `MainSplittedFileFinderComponent`, and the read-only `DatabaseDiagnosticProbe`/`NasDiagnosticProbe` used by `EnvironmentDiagnosticCommand`), and **`command`** classes (e.g. `ProgramCommand`, `ExecutedFileCommand`, `SplittedFileCommand`, `CreatedFileCommand`) that are the actual business-logic layer other modules call into. New cross-cutting logic (DB queries, NAS/shell/tool interaction, domain rules) belongs here, not in `manager`/`processor`.
- **`manager:api`** is a thin Spring MVC layer (`controller`/`response`/`exception`) over `core`'s commands — controllers should stay free of business logic.
- **`manager:console`** is a Clikt-based CLI (`commands/`) over the same `core` commands, for operator scripts (search/get/modify/delete, plus `duplicates`, which lists programs recorded from the same broadcast via `core`'s `DuplicateProgramCommand`, and `stats`, read-only library totals grouped by channel/month via `core`'s `LibraryStatsCommand`, and `doctor`, which validates DB/NAS settings and probes connectivity (optionally NAS write access with `--check-write`) via `core`'s `EnvironmentDiagnosticCommand`; it bootstraps through `DoctorBootstrap` without a Spring context so broken configuration stays diagnosable).
- **`processor:infrastructure`** turns raw recordings into the state `manager` can track. `pipeline/FileProcessingPipeline` is a 4-stage pipeline (drop check → TsSplitter → compress + upload to NAS → Amatsukaze add-task); each stage up to compression rolls back itself and every earlier stage in reverse order before rethrowing on failure; once the upload starts nothing is rolled back, so registration/split/upload evidence survives for reconciliation. The drop-check stage uses `external/tsselect/TsSelectClient` (local to this module), which counts dropped frames in-JVM via the `me.pinfort:tsselect` library's `tsDump` mode — it replaced the old Windows-only `tsDropChkx64.exe` shell-out (`DropChkClient`, removed). The same stage then runs `external/ts/EmergencyBroadcastDetector`, a second in-JVM pass that parses PAT/PMT and the superimpose PES (ARIB caption data via `AribCaptionData`) and records the result as tags via `core`'s `ExecutedFileTagCommand` (see below). `pipeline/PathProcessingRunner` expands a file/directory into `.m2ts` files and runs the pipeline per file, reporting failures to Slack via `external/slack/SlackClient` (`SLACK_WEBHOOK_URL`, no-op when unset). `pipeline/AfterEncodeRunner` is the tail of the same flow, called once Amatsukaze finishes an encode: it uploads the encoded outputs to the video-store NAS, registers them as `created_file` rows, deletes the local outputs and originals and completes the program. Both the compressed original and encoded outputs go through `core`'s `VerifiedUploadCommand`: a durable `upload_manifest` row (claim token + state machine NEW → CREATING → TRANSFERRING → TEMP_VERIFIED → FINALIZING → FINALIZED → REGISTERED) records ownership before each NAS effect, uploads go to an exclusively created temporary file that is verified and renamed without replacement, and a local file is deleted only after its upload is REGISTERED and its SHA-256 re-checked (see `backend/docs/verified-uploads.md`). Unlike `FileProcessingPipeline` it does **not** roll back — a failure is logged, sent to Slack and leaves the program in `ERROR`.
- **`processor:console`** is a Clikt CLI (`tvpcli`, `commands/TsVideosProcessor`) with six subcommands: `process` (`commands/ProcessCommand` over `PathProcessingRunner`) takes one or more paths, `after-encode` (`commands/AfterEncodeCommand` over `AfterEncodeRunner`) reads Amatsukaze's post-encode environment variables, `reset` (`commands/ResetCommand` over `pipeline/ResetRunner`) takes one recorded-file path and undoes its processing — resolves `executed_file` → `program`, then deletes every `splitted_file`/`created_file` row, the NAS copies and the local split files, and finally the `program` and `executed_file` rows (the original recording is kept), and `release-claim` (`commands/ReleaseClaimCommand` over `VerifiedUploadCommand.claimed`/`releaseAbandonedClaim`) clears upload-manifest claims a crashed process left behind, after confirmation and without touching state or NAS. `status` and `history` (`commands/HistoryCommand` over `core`'s `ProcessingHistoryCommand`) are read-only views of the per-job attempt/stage history that `process`/`after-encode` record (`--job-id`/`--all`, `--json`; see `backend/docs/processing-history.md`). `reset` prompts for confirmation via `component/UserQuestionComponent` (a port of the Python `reset.py`/`RemoveProgram`; no rollback). All mutating subcommands accept `-d`/`--dry-run`; `process`/`after-encode` render drop-check/compress/upload progress bars via `display/ProgressPrinter`. Runs as a non-web Spring Boot app (`CommandLineRunner`).
- Detector results are stored as **tags**, not columns: `executed_file_tag` holds the tags a detector attached (names in `core/domain/ExecutedFileTag`) and `executed_file_check` records which detectors ran (`ExecutedFileCheck`), so "not detected" and "not checked" stay distinguishable (`ProgramDetail.detected(tag)`). Add a new detector by adding constants and calling `ExecutedFileTagCommand.recordCheck` — no schema change. `ExecutedFileCommand.delete` also removes the file's tags/checks. Production DDL is in [backend/README.md](backend/README.md).
- MyBatis is used directly (mapper interfaces + XML/annotations under `core/external/database`), not JPA/Hibernate.
- Tests use Kotest (not JUnit assertions) + MockK (not Mockito — explicitly excluded in `build.gradle.kts`) + Testcontainers-backed MariaDB for anything touching the DB. The schema's single source of truth is the numbered SQL files in `core/src/main/resources/db/migrations/`; the Testcontainers init script (`ddl/01_create_database.sql`) is generated from them by `core:generateTestDdl`, and `docker-compose.test.yml` mounts them too (add a mount line for each new migration). Test seed data is `core/src/test/resources/ddl/02_insert_data.sql`.

### Frontend

- Next.js App Router; pages under `frontend/app/**`, shared UI under `frontend/components/`.
- All backend calls go through `frontend/lib/api/` (`client.ts` has the shared `fetchJson`/`ApiError` helpers; `programs.ts`/`video.ts` wrap specific endpoints). Add new backend calls there rather than calling `fetch` directly from components/pages.
- The video playback/download page fetches video bytes directly from the backend in the browser (not proxied through Next.js), so `NEXT_PUBLIC_API_BASE_URL` must be a browser-reachable URL.
- Every component/page/lib module has a co-located `*.test.tsx`/`*.test.ts` file (Vitest + Testing Library + jsdom, configured in `vitest.config.ts`). Follow that co-location pattern for new files.
