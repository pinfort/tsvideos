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

## manager:console の整合性検証 (`tvmcli verify`)

`verify` は全番組、`verify --program 123` は指定番組の `created_file`（圧縮済み原本・エンコード済みファイル等の NAS 成果物）を読み取り専用で検証します。DB、NAS、ローカルファイルを変更しません。`executed_file` / `splitted_file` のローカル処理用パスは対象外です。

```bash
./gradlew manager:console:bootRun --args="verify --program 123"
./gradlew manager:console:bootJar
java -jar manager/console/build/libs/console.jar verify --json > verification.json
```

自動処理では Gradle の出力を含まない実行可能 jar を使用してください。`--json` の stdout は UTF-8 の単一 JSON オブジェクト（`schemaVersion: 1`, `summary`, `findings`）です。ログ・エラーは stderr に出力します。各 finding は `programId`, `fileId`, `file`, `status`, UTC の `checkedAt`, バイト単位の `expectedBytes` / `actualBytes`, `reason` を含みます。番組に成果物が未登録の場合は `fileId` / `file` / サイズを null にした `UNCHECKED` を一件出します。summary は全ステータスの件数で、これらの未登録番組も含みます。

| 状態 | 意味 |
| --- | --- |
| `VERIFIED` | 通常ファイルが存在し、記録サイズと一致 |
| `MISSING` | 両 NAS の確認に成功したがファイルが存在しない |
| `SIZE_MISMATCH` | 存在するがサイズが不一致 |
| `INACCESSIBLE` | 接続・権限・メタデータ取得などの失敗。削除済みとは判断しない |
| `UNCHECKED` | NAS 転送未完了、未登録、無効なパス／サイズ、または通常ファイルでない |

既存の NAS 解決順（video store → original store）を使い、DB パス内の baseDir は重ねて付加しません。先の NAS がアクセス不能なら、後の NAS のファイルを使って検証成功とはしません。個別ファイルの問題があっても後続ファイルを検証します。サイズ一致は内容の完全性を保証せず、検証後にファイルが変化することもあります。

終了コードは `0`（全件正常・空ライブラリ）、`1`（DB 等の実行失敗）、`2`（引数不正）、`3`（指定番組がない）、`4`（`VERIFIED` 以外の finding がある）です。

自動処理用の共通規約として、`manager:console` の全サブコマンドで引数・オプションの使用方法エラーを終了コード `2` に統一しています。また、全サブコマンドのログ出力先を stderr とし、ルートロガーの既定レベルを `WARN` に設定しています。パッケージ別のログレベル設定も適用されます。これらは `verify` や `--json` 指定時だけでなく、通常の対話的な実行にも適用されます。

### 深いメディア検証の別モード仕様

デフォルトは SMB の存在・通常ファイル種別・サイズだけを調べ、ダウンロードや ffprobe は実行しません。将来の任意モード `--deep` は、通常検証に成功したメディアだけを別ステップでプローブし、読み取り／デコードエラー・ストリーム情報を独立した `mediaCheck` 結果として返す設計です。明示的な実行時間・転送量の上限と一時ファイルの後処理が必要であり、通常検証の `VERIFIED` を「デコード確認済み」と読み替えません。この PR はこのモードの境界を定義するもので、`--deep` の実装は含みません（未対応オプションとして終了コード `2`）。

## CLI のバージョン確認

`tvmcli`（`manager:console`）と `tvpcli`（`processor:console`）は `--version` でバージョンと git コミットハッシュを `tvmcli version 0.0.1-SNAPSHOT (d87cab7)` の形式で表示します。どちらもビルド時に `core` のリソース（`version.properties`）へ埋め込まれます（バージョンは Gradle プロジェクトバージョン、コミットハッシュは `git rev-parse --short HEAD`）。git リポジトリ外でビルドした場合（Docker ビルドなど）はコミットハッシュが取得できないため、バージョンのみを表示します。

```bash
./gradlew manager:console:bootRun --args="--version"
./gradlew processor:console:bootRun --args="--version"
```

### Library health dashboard

The web `/health` page shows failed recordings, dropped frames, and the last saved NAS verification results. See [library-health.md](docs/library-health.md) for the required migration, UTC timestamps, explicit scan behavior, and interrupted-run recovery. GET never triggers a NAS scan.
