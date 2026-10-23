# diagnostics

DB/NAS の環境診断と診断結果を扱うモジュール。#136 で `core` から抽出した。設計上の位置づけと契約は [module-boundaries.md](../docs/module-boundaries.md) の §2、§11.5、§11.7 を参照。

- **依存してよいプロジェクトは `storage` だけ。** `core`・`catalog`・`processor:*`・`manager:*` には依存せず、ソースは `me.pinfort.tsvideos.diagnostics` と `me.pinfort.tsvideos.storage` 以外のパッケージを参照しない。NAS には storage の診断用接続 `DiagnosticNasClient` (公開 API) を通してだけ触れ、jcifs を直接使わない。
- 利用側 (`processing` の `ProcessingPreflightCommand` / `LocalPreflightProbe`、`manager:console` の `doctor`、`processor:console` の `preflight` と `process` の事前ゲート) は `implementation(project(":diagnostics"))` に依存し、公開パッケージ `api` と `domain` の型だけを使う。依存は利用側 → diagnostics の片方向で、diagnostics に互換用の型は置かない。
- 処理用のツール・設定・入力の検査 (`ProcessingPreflightCommand`、`LocalPreflightProbe`、`EncoderConnectivityProbe`) は processing の責務で、#164 で `core` から processing モジュールに移した。

## パッケージ

| パッケージ | 内容 |
| --- | --- |
| `me.pinfort.tsvideos.diagnostics.api` (公開) | `EnvironmentDiagnosticCommand` (設定の検証と DB/NAS への到達確認)、`DatabaseDiagnosticProbe` (短いタイムアウトの独立した接続で `SELECT 1`)、`NasDiagnosticProbe` (一覧の確認と、明示した場合だけの隔離された書き込み確認)、`DiagnosticEnvironment` (Spring の設定ファイルと `--spring.*` 引数だけを読む `StandardEnvironment`)、`diagnosticCancellation` (割り込みの保存) |
| `me.pinfort.tsvideos.diagnostics.domain` (公開) | `DiagnosticReport`、`DiagnosticCheck`、`DiagnosticStatus` |

内部サービスがないので内部パッケージは作っていない。作る場合は境界チェックの対象になる (外部から参照できない)。

## 組み立て

- **Spring コンテキストなしでコンストラクタだけで組み立てられる。** `DoctorBootstrap` / `PreflightBootstrap` は `DiagnosticEnvironment.load(args)` で設定を読み、`EnvironmentDiagnosticCommand(environment, DatabaseDiagnosticProbe(), NasDiagnosticProbe(DiagnosticNasClient.create()))` を手で組み立てる。DataSource・ツール・アプリのコマンドグラフを作らないので、設定が壊れていて Spring が起動できなくても診断できる。
- Spring で使う場合は各クラスが `@Component` なので、実行アプリが `scanBasePackages` に `me.pinfort.tsvideos.diagnostics` を含める (processing の `ProcessingPreflightCommand` が `EnvironmentDiagnosticCommand` の Bean を要求するため 3 つの実行アプリすべて。manager の 2 アプリが processing をスキャンするのは #165 までの移行中だけ)。`DiagnosticNasClient` の Bean は storage が登録する。
- 設定ファイルとプロファイルを持たない。読むキー (`spring.datasource.*`、`samba.*`) と既定値は実行アプリと core / storage の設定のまま。
- DB の確認は `org.mariadb.jdbc:mariadb-java-client` (runtimeOnly) を `DriverManager` で使う。接続プールを作らず、読み取り専用で、ライブラリの行を変更しない。

## 境界チェック

- `./gradlew diagnostics:checkDiagnosticsBoundaries` (`check` / `build` に含まれる): プロジェクト依存が `storage` だけであること、diagnostics のソース (テストを含む) が他モジュールのパッケージを参照しないこと、diagnostics 以外の Kotlin ソースが `api` / `domain` 以外の diagnostics パッケージを参照しないこと (コメント行と、スキャン用のパッケージ名そのものは許可)。先に `checkDiagnosticsBoundaryRules` が、違反を含むフィクスチャ (逆向きのプロジェクト依存、diagnostics から core/catalog への参照、外部からの内部パッケージの参照) がすべて検出され、許可されるもの (storage 依存、公開パッケージ、スキャン用のパッケージ名、コメント) が検出されないことを確かめる。
- `DiagnosticsContractTest`: 公開コンストラクタだけで Spring なしに組み立てて診断できること、公開シグネチャに jcifs の型が出ないこと、diagnostics のソースが jcifs を import しないこと。

## テスト

`./gradlew diagnostics:test`。`DatabaseDiagnosticProbeTest` は Testcontainers で MariaDB を起動するため Docker が必要。`EnvironmentDiagnosticCommandTest` と `NasDiagnosticProbeTest` はモックで動く (Samba 実装そのものは storage の `SambaDiagnosticNasClientTest`)。
