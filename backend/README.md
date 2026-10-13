This is the backend for tsvideos, built with Kotlin, Spring Boot, and Gradle.

## モジュール構成

- `core`: 共通コンポーネント（DB 接続、Samba (NAS) 接続など）
- `manager:infrastructure` / `manager:console` / `manager:api`: 処理済みの録画データを管理するためのツール（`manager:api` が Web API）
- `processor:infrastructure` / `processor:console`: 録画データを処理して管理ツールで管理できる状態にするためのアプリケーション

## ローカルでの動作確認

`docker-compose.test.yml`（リポジトリルート）で MariaDB と Samba (NAS) を起動すると、`core/src/main/resources/application-core.yaml` のデフォルト値のまま環境変数なしで `manager:api` をローカル起動できます。

```bash
docker compose -f ../docker-compose.test.yml up -d
./gradlew manager:api:bootRun
```

DB や NAS の接続先を変える場合は、以下の環境変数で上書きできます。

- `DATABASE_CONNECTION` / `DATABASE_USER_NAME` / `DATABASE_PASSWORD`
- `VIDEO_STORE_NAS_URL` / `VIDEO_STORE_NAS_USERNAME` / `VIDEO_STORE_NAS_PASSWORD` / `VIDEO_STORE_NAS_BASE_DIR`
- `ORIGINAL_STORE_NAS_URL` / `ORIGINAL_STORE_NAS_USERNAME` / `ORIGINAL_STORE_NAS_PASSWORD` / `ORIGINAL_STORE_NAS_BASE_DIR`

## コマンド

- `./gradlew build` - 全モジュールをビルド
- `./gradlew test` - テストを実行（テストは Testcontainers で MariaDB を起動するため Docker が必要）
- `./gradlew ktlintCheck` - Kotlin のコードスタイルチェック

## processor:console (`tvpcli`)

`tvpcli` は3つのサブコマンドを持ちます。

- `process <パス>...` — 録画ファイル（または `.m2ts` を含むディレクトリ）をドロップチェック(tsselect) → TsSplitter → 圧縮・NAS アップロード → Amatsukaze タスク登録 のパイプラインで処理します。
- `after-encode` — Amatsukaze のエンコード実行後バッチから呼び出します。エンコード済みファイルを `created_file` として登録し、NAS へアップロードしたうえで元ファイルを削除し、番組を `COMPLETED` にします。
- `reset <録画ファイル>` — 指定した録画ファイルの処理をリセットします。`executed_file` → `program` を辿り、`splitted_file` / `created_file` の各レコード、NAS 上のファイル、ローカルに残った分割ファイルを削除したうえで `program` と `executed_file` のレコードも消します（元の録画ファイルは残します）。実行前に確認を求めます。ロールバックは行いません（Python 版 `reset.py` の移植）。

いずれも `-d` / `--dry-run` で書き込みを行わずに実行できます。
`process --dry-run` は登録済み確認・ファイル名検証・ドロップチェック・長さの取得まで行い、以降の予定をログに出します。DB 登録、分割、圧縮、NAS アップロード、エンコード投入、ロールバックは実行しません。

```bash
./gradlew processor:console:bootRun --args="process D:\\rec\\foo.m2ts"
./gradlew processor:console:bootRun --args="after-encode"
./gradlew processor:console:bootRun --args="reset D:\\rec\\foo.m2ts"
```

`after-encode` は Amatsukaze が実行後バッチに渡す以下の環境変数を読みます（同名のオプションでも指定できます）。

| 環境変数 | オプション | 内容 |
| --- | --- | --- |
| `ITEM_ID` | `--item-id` | Amatsukaze のアイテムID |
| `IN_PATH` | `--in-path` | 入力ファイルパス（`succeeded` ディレクトリへ移動済み） |
| `FILES` | `--files` | 出力ファイル群（`;` 区切り） |
| `SUCCESS` | `--success` | `1` のときのみエンコード成功として扱う |
| `ERROR_MESSAGE` | `--error-message` | 失敗理由（失敗したときのみ） |

`after-encode` はロールバックを行いません。NAS へのアップロードやローカルファイルの削除が済んだ後に失敗を巻き戻すことはできないため、失敗時はログと Slack 通知（`SLACK_WEBHOOK_URL`）を行い、番組を `ERROR` にします。

## CLI のバージョン確認

`tvmcli`（`manager:console`）と `tvpcli`（`processor:console`）は `--version` でバージョンと git コミットハッシュを `tvmcli version 0.0.1-SNAPSHOT (d87cab7)` の形式で表示します。どちらもビルド時に `core` のリソース（`version.properties`）へ埋め込まれます（バージョンは Gradle プロジェクトバージョン、コミットハッシュは `git rev-parse --short HEAD`）。git リポジトリ外でビルドした場合（Docker ビルドなど）はコミットハッシュが取得できないため、バージョンのみを表示します。

```bash
./gradlew manager:console:bootRun --args="--version"
./gradlew processor:console:bootRun --args="--version"
```
## Manager CLI structured output

See [manager/console/README.md](manager/console/README.md) for JSON fields,
ID-only output, stdout/stderr separation, and exit codes.

### Library statistics

`tvmcli stats [--group-by CHANNEL|MONTH] [--recorded-from 2026-01-01T00:00:00] [--recorded-to 2026-02-01T00:00:00] [--json]`

The read-only core aggregation is shared with future web consumers. Bounds use the recording environment's local timezone, include the start and exclude the end; offsets are rejected. Month groups use local `YYYY-MM`. Missing recording metadata has a null group key (human output `(unknown)`); date filters exclude those programs. Empty libraries return zero totals and empty groups with exit 0.

Program counts count each program ID once, including orphan program rows; failed programs have status `ERROR`. Dropped frames sum known nonnegative counts per program; negative or absent counts contribute to `unknownDropsProgramCount`, not the sum. Two programs referring to one executed recording still count as two programs.

Storage sums distinct created-file IDs associated with selected programs, including all statuses. Multiple programs referencing the same recording do not multiply file bytes. `originalTsBytes` includes MIME `video/vnd.dlna.mpeg-tts` with encoding `gzip`; `encodedBytes` includes other `video/*` MIME types; `otherBytes` includes uncompressed TS, subtitles, unknown MIME and other artifacts. Original executed-file sizes and split-file sizes are not added. `movedFileBytes` covers `FILE_MOVED`; `pendingFileBytes` covers all other statuses. These are registered metadata bytes, not proof of remote existence or current size. No NAS scan occurs; `verifiedPhysicalBytes` is always null, even for an empty library. Unassociated created-file rows are outside the selected program library.

The command reads metadata in one repeatable-read transaction. Group file totals use the same deduplication as overall totals. JSON uses `{schemaVersion:1, stats:{groupBy,recordedFrom,recordedTo,totals,groups}}`, integer counts/bytes, and nullable physical usage. Human output labels units and verification status. `--ids-only` is unsupported; invalid options/ranges exit 2 and operational errors exit 1 without partial JSON. The aggregation materializes selected metadata (not file contents) in memory.

### Storage overview

`GET /api/v1/storage` and the web `/storage` page are read-only views over the shared library statistics. The largest 20 programs are ordered by distinct created-file metadata bytes descending, then program ID ascending, including programs without files. Per-program sizes are not additive when programs share files.

Duplicate candidates use the existing duplicate detector and its keep recommendation. The estimated reclaimable bytes assume removing every nonrecommended program across all displayed groups. A normalized NAS path is counted once only when every program referencing it is in that removal set. Paths referenced by retained programs are excluded; separator/case/dot aliases are normalized conservatively, including when distinct NAS destinations cannot be inferred. Conflicting sizes use the smallest nonnegative registered value. Pending records are included and labeled; both physical usage and verified reclaimable bytes remain null. No file is inspected or deleted. Overview statistics, references and duplicate detection use one repeatable-read transaction.
