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

### Duplicate recording review

`GET /api/v1/programs/duplicates` enriches the existing duplicate detector's groups in one repeatable-read snapshot. `/duplicates` compares channel, local recording time, duration, drops, status and `FILE_MOVED` files side by side. The GET performs no NAS calls; available-file labels describe registered status, not fresh physical verification.

The same existing keep order is shown explicitly: fewer known drops (unknown is ranked last), COMPLETED/REGISTERED/ERROR, longer duration, larger original-recording size, then lower ID. The reason compares the recommendation with the next-ranked member; all winning values and the full ordered criteria are exposed. This is a recommendation, not forced retention. All checkboxes start empty, including the recommended member; selecting it manually makes it a deletion target.

Cleanup reuses the bulk preview/apply commands and UI, including exact file/record preview, explicit confirmation, stale fingerprints, shared-path protection, per-target results and preservation of the original local recording. Results survive refresh even if a duplicate group disappears. No automatic cleanup occurs.

### Reviewed cleanup plans (`manager:console`, #61)

`cleanup plan` only reads registered metadata. Choose exactly one mode:

```bash
java -jar manager.jar cleanup plan --duplicates > cleanup.json
java -jar manager.jar cleanup plan --duplicates --channel 'channel-id' --keep 123 > cleanup.json
java -jar manager.jar cleanup plan --recorded-before 2025-01-01T00:00:00 --channel 'channel-id' > cleanup.json
java -jar manager.jar cleanup apply cleanup.json --dry-run
java -jar manager.jar cleanup apply cleanup.json --yes
```

On PowerShell, save stdout with `Set-Content -Encoding utf8`; plan JSON and CLI stdout use UTF-8. Review `targets`, `keptTargets`, `groups`, file paths, eligibility and estimates before applying. Duplicate mode uses the same broadcast grouping and keep recommendation as the duplicate review page. Repeated `--keep ID` options override one kept copy per group. Retention requires an explicit exclusive local recording-time cutoff; its optional channel matches exactly. Unknown recording dates are excluded. Retention is a separate explicit policy and does not preserve duplicate copies automatically.

Schema version 1 includes UTC creation time, criteria, chosen keep IDs, exact file snapshots and fingerprints. A plan supports at most 100 targets and 100 groups; narrow criteria if that limit is exceeded. There is no implicit batching. Size estimates are registered metadata bytes, not verified physical space or promised reclaimable bytes. Plan generation and dry-run never scan or contact NAS and never mutate the database. A dry-run is a point-in-time validation, not a reservation.

Apply requires `--yes`. Imported paths are review data only; the existing bulk deletion command resolves current metadata and performs deletion. Original recordings are preserved. Shared paths, pending files and other existing bulk eligibility protections still apply. Each target runs in its own transaction and locks registered files, then programs, executed files and split files in a fixed order before checking the complete candidate selection, kept-copy snapshots and duplicate membership again. These broad locks can delay other library operations while a target is being deleted. Opposite keep choices in concurrent cleanup plans cannot delete every copy: the later operation becomes stale. This guarantee applies at cleanup application time; a later independent explicit web deletion is not prohibited by a permanent keep policy.

Changed schema/criteria/snapshots, missing kept copies or changed groups require regeneration and review. Only this invocation's confirmed successful deletions are removed from the expected group membership when checking later targets. Partial NAS failures may leave physical files partly removed even if database changes roll back. Inspect the outcome and paths, regenerate and review before retrying. There is no automatic deletion or background schedule.

Output is JSON (the optional `--json` flag is accepted). Exit codes: `0` all targets succeeded/validated (including an empty plan), `1` operational failure, `2` invalid input or missing confirmation, `3` missing explicit resource, `4` unresolved/partial target outcomes including stale plans. `130` indicates interruption: the interrupt flag is restored and no later target is started. Inspect current state before retrying because completed targets and partial NAS effects can remain. Diagnostics go to stderr; per-target results remain in stdout JSON.
