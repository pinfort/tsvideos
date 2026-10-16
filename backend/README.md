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

### 緊急警報放送・文字スーパーの検出とタグ

`process` はドロップチェックの直後に録画ファイルをもう一度読み、次のものを検出して録画にタグとして付けます。

| タグ (`executed_file_tag.tag`) | 内容 |
| --- | --- |
| `ews` | 緊急警報放送 — PMT の緊急情報記述子 (tag 0xFC) に開始/継続中のエントリがある |
| `superimpose` | 文字スーパー — PMT 上の文字スーパー ES (component_tag 0x38〜0x3F) に文字を含む本文が流れている（画面消去だけのデータは数えない） |

検出対象は分割前の元ファイルに含まれる全サービスです。録画対象の `service_id` は現在の入力情報に含まれず、PAT の先頭サービスを録画対象とみなすこともできないため、サービスの絞り込みは行いません。全サービス録画では、視聴対象以外のサービスに関する緊急情報記述子や文字スーパーでもタグが付くことがあります。タグは視聴対象サービスでの表示を保証するものではありません。

両方を検出した場合だけ早期終了します。通常の「検出なし」の録画ではファイル全体を追加で 1 回読むため、ドロップチェックと合わせて 2 回の走査になります。特に NAS 上の大きな録画では I/O と処理時間が増えます。走査の統合と PES バッファのコピー削減は別途対応します。

EOF 時点で未完成の PES は破棄します。長さ 0 の PES は次の開始パケットまで確定しないため、末尾のその PES だけに文字がある場合は検出できません。回帰テストは合成 TS を使用しています。実録画では、EWS・文字スーパーを含まない NHK の録画 2 本（9.7GB）で誤検出しないこと、および同じ解析処理で字幕の本文が正しく読めることを確認済みですが、EWS・文字スーパーを含む実録画による検証はまだです。

タグは録画全体に対して付きます。録画中に一度でも条件を満たせば（数秒だけの緊急警報放送でも）タグが付き、どの時間帯だったかは記録しません。

あわせて、検出処理を実行したこと自体を `executed_file_check`（`checker = 'emergency_broadcast'`）に記録します。タグが無い録画が「検出なし」なのか「未検査」（検出機能の導入前に登録された録画）なのかは、このチェックの有無で区別します。`tvmcli get` とフロントエンドの番組詳細には「あり / なし / 未検査」で表示されます。

「未検査」になるのは次の場合です。

- 検出機能の導入前に登録された録画。既存の録画を後から検査する CLI はまだ無いため、これらは未検査のまま残ります。
- 検出処理が失敗した録画（TS として読めないなど）、または検出結果の保存に失敗した録画。検出は付加情報なので、どちらの場合も warn ログを出して録画の処理自体は続行します。

放送局が映像に焼き込んだテロップ（多くのニュース速報・地震速報）は TS のデータとしては存在しないため、検出できません。

新しい検出を足すときは `core` の `ExecutedFileTag` / `ExecutedFileCheck` に定数を足し、検出処理から `ExecutedFileTagCommand.recordCheck` を呼ぶだけで、DB のスキーマ変更は不要です（画面の表示名は `frontend/lib/api/tags.ts` と `ProgramDetailToTextComponent` に追加します）。`recordCheck` はその検出処理が付けるタグ（`ExecutedFileTag.CHECKERS`）を置き換えるので、再検査で結果が変わっても古いタグは残りません。

`executed_file_tag` / `executed_file_check` には外部キーや CASCADE がありません。タグと実行記録は `ExecutedFileCommand.delete`（パイプラインのロールバック・`reset`・`tvmcli delete`）でだけ一緒に消えるので、SQL を直接実行して `executed_file` の行を消した場合は、両テーブルの該当行も手で消してください。

既存の DB には次のテーブルを一度だけ作成してください。**この機能を含むビルドをデプロイする前に**作成する必要があります。番組詳細の取得（`ProgramCommand.findDetail`）が両テーブルを参照するため、テーブルが無いと manager の API・CLI の詳細表示が失敗します（processor 側は処理を止めず、録画が「未検査」になるだけです）。

```sql
CREATE TABLE executed_file_tag (
    executed_file_id bigint(20) NOT NULL,
    tag varchar(64) NOT NULL,
    PRIMARY KEY (executed_file_id, tag),
    KEY tag (tag)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE executed_file_check (
    executed_file_id bigint(20) NOT NULL,
    checker varchar(64) NOT NULL,
    checked_at datetime NOT NULL,
    PRIMARY KEY (executed_file_id, checker)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
```

## CLI のバージョン確認

`tvmcli`（`manager:console`）と `tvpcli`（`processor:console`）は `--version` でバージョンと git コミットハッシュを `tvmcli version 0.0.1-SNAPSHOT (d87cab7)` の形式で表示します。どちらもビルド時に `core` のリソース（`version.properties`）へ埋め込まれます（バージョンは Gradle プロジェクトバージョン、コミットハッシュは `git rev-parse --short HEAD`）。git リポジトリ外でビルドした場合（Docker ビルドなど）はコミットハッシュが取得できないため、バージョンのみを表示します。

```bash
./gradlew manager:console:bootRun --args="--version"
./gradlew processor:console:bootRun --args="--version"
```

Processor deployment and recovery: [processing identity and reconciliation](docs/processing-idempotency.md). Apply migration 064 before upgrading.

既存の DB には次のテーブルを一度だけ作成してください（`core/src/main/resources/db/migrations/064_processing_job.sql` と同じ内容です）。**processor を停止してから、この機能を含むビルドをデプロイする前に**作成する必要があります。

```sql
CREATE TABLE IF NOT EXISTS processing_job (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 identity_key CHAR(64) CHARACTER SET ascii NOT NULL UNIQUE,
 input_path TEXT COLLATE utf8mb4_bin NOT NULL,
 input_size BIGINT NOT NULL,
 modified_millis BIGINT NOT NULL,
 state VARCHAR(40) NOT NULL,
 executed_file_id BIGINT NULL,
 callback_claim VARCHAR(36) NULL,
 callback_manifest CHAR(64) CHARACTER SET ascii NULL,
 created_at DATETIME(6) NOT NULL DEFAULT UTC_TIMESTAMP(6),
 updated_at DATETIME(6) NOT NULL DEFAULT UTC_TIMESTAMP(6),
 -- At most one job per executed file: retry() clears the link and reset removes the job.
 KEY executed_file_id (executed_file_id),
 -- Lookup hint for findByPath only; identity_key is what enforces uniqueness.
 KEY input_path (input_path(255))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
```

## Manager CLI structured output

See [manager/console/README.md](manager/console/README.md) for JSON fields,
ID-only output, stdout/stderr separation, and exit codes.
