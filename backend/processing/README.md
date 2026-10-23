# processing

録画の処理 (processor 側) の業務を扱うモジュール。#164 で `core` から処理専用のコードのうち、ジョブ所有権・処理履歴・録画品質・再試行・中断・preflight・外部ツール/shell・処理補助を抽出した。設計上の位置づけと契約は [module-boundaries.md](../docs/module-boundaries.md) の §2、§4、§7.6、§11.3、§11.7 を参照。

現在 processing にないもの:

- 検証付きアップロード (`VerifiedUploadCommand`、`UploadManifest` / `VerifiedUploadReceipt`、`UploadManifestMapper`) は #165 まで `core` に残る。
- pipeline (`FileProcessingPipeline`、`AfterEncodeRunner`、`PathProcessingRunner`、`ResetRunner`、`RollbackRunner`) と tsselect・TS 解析・Slack は #166 まで `processor:infrastructure` に残る。
- `ApplicationVersion`、`LoggerConfig`、DataSource の設定 (`application-core.yaml`) は #139 まで `core` に残る。

依存の規則:

- **依存してよいプロジェクトは `catalog`・`storage`・`diagnostics` だけ。** `core`・`library`・`console-common`・`processor:*`・`manager:*` には依存せず、ソースは `me.pinfort.tsvideos.processing`・`catalog`・`storage`・`diagnostics` 以外のパッケージを参照しない。`database-schema` は DB テストのテストフィクスチャとしてだけ使う。
- 台帳は catalog の公開操作、NAS は storage の公開型、DB/NAS の環境診断は diagnostics の公開操作だけで扱う。
- 利用側は `implementation(project(":processing"))` に依存し、公開パッケージ `api` (下位の `api.config` / `api.shell` / `api.tool` を含む) と `domain` の型だけを使う。Mapper には触れない。
- **移行中だけの逆向きの依存**: core に残る `VerifiedUploadCommand` が processing の `ProcessingRetry` / `ProcessingCancellation` / `ProcessingIdentity` を使うので、`core` → `processing` を #165 まで許す。manager の 2 アプリも core をスキャンするので、起動を保つために移行中だけ processing をスキャンし、Mapper を登録し、`processing` プロファイルを include する。#165 でアップロードを processing へ移すときにどちらも外す。`processing` → `core` は禁止。

## パッケージ

| パッケージ | 内容 |
| --- | --- |
| `me.pinfort.tsvideos.processing.api` (公開) | 公開操作: `ProcessingJobCommand`、`ProcessingJobClaimCommand`、`ProcessingHistoryCommand`、`RecordingQualityCommand`、`ProcessingPreflightCommand`。補助部品: `ProcessingCancellation`、`ProcessingRetry` 一式 (`ProcessingRetryRuntime`、`RetryOperation`、`RetryRefusedException`、`TransientProcessingFailure`)、`RecordingQualityInspector`、`LocalPreflightProbe` / `EncoderConnectivityProbe`、`CompressComponent`、`MainSplittedFileFinderComponent`、`NasDestinationResolver`、`MimeTypeComponent`、`ValidateCompletedComponent`、`RunningPlatformComponent`、`expandRecordingInputs`。Mapper の登録先 `ProcessingPersistence.MAPPER_PACKAGE` |
| `me.pinfort.tsvideos.processing.api.config` (公開) | 設定クラス `ProcessorToolConfigurationProperties` (`processor-tool.*`)、`ProcessingRetryConfigurationProperties` (`processor-retry.*`)、`RecordingQualityConfigurationProperties` (`processor-quality.*`) |
| `me.pinfort.tsvideos.processing.api.shell` (公開) | `ShellClient`、`ShellComponent`、`ShellResult` |
| `me.pinfort.tsvideos.processing.api.tool` (公開) | `TsSplitterClient`、`AmatsukazeAddTaskClient`、`DurationProbeClient`、`RecordingStreamProbeClient`、`VideoResolutionProbeClient` |
| `me.pinfort.tsvideos.processing.domain` (公開) | ジョブ (`ProcessingJob`、`ProcessingIdentity`)、履歴 (`ProcessingHistory.kt` の `ProcessingAttempt`、`ProcessingStageEvent`、`ProcessingStatus`、`ProcessingErrorCode` など)、品質 (`RecordingQuality.kt` の `QualityAction`、`RecordingQualityPolicy`、`RecordingQualityEvaluator` など、`RecordingQualityReport`)、`FileName`、例外 (`TsVideosException`、`InvalidFileNameException`、`InvalidEnvironmentException`、`ShellCommandTimeoutException`、`QualityReleaseRefusedException`) |
| `me.pinfort.tsvideos.processing.persistence` (internal) | MyBatis の Mapper (`ProcessingJobMapper`、`ProcessingHistoryMapper`、`RecordingQualityMapper`) と `GeneratedKeyHolder` (catalog・library と同じく module 内の複製)。トップレベルの宣言はすべて `internal` (または `private`) |

外部ツール・shell・処理補助は、pipeline が processing に移るまで (#166) `processor:infrastructure` が使うので、移行中は `api` の下に置いて公開している。Mapper を引数に取る `ProcessingJobCommand`、`ProcessingJobClaimCommand`、`ProcessingHistoryCommand`、`RecordingQualityCommand` のコンストラクタは `internal`。processing の外では Spring の Bean として注入するか、テストではモックにする。`ProcessingPreflightCommand`・`LocalPreflightProbe`・`EncoderConnectivityProbe` は公開コンストラクタのままで、`processor:console` の `PreflightBootstrap` が Spring コンテキストなしに組み立てる。`ProcessingJobCommand.reconcileSubmission` など本番の呼び出し元がない操作も残している (module-boundaries.md §13 U4)。`application` パッケージは内部サービスがないので作っていない。

## 組み立て

- processing は DataSource・`PlatformTransactionManager`・`SqlSessionFactory`・`Logger` を定義しない。実行アプリは `scanBasePackages` に `me.pinfort.tsvideos.processing` を、`@MapperScan` に `ProcessingPersistence.MAPPER_PACKAGE` を、`@ConfigurationPropertiesScan` に `me.pinfort.tsvideos.processing.api.config` を加えて、processing の Mapper も catalog・core と同じ 1 組の `SqlSessionFactory` とトランザクションマネージャに登録する。`persistence` のパッケージ名は processing の外に書かない。
- 設定は [application-processing.yaml](src/main/resources/application-processing.yaml) (`processing` プロファイル) に `processor-tool.*` と `processor-preflight.space-multiplier` が入る。#164 で `core` の `application-core.yaml` から移した。キー・既定値・環境変数 (`TS_SPLITTER_PATH`、`AMATSUKAZE_ADD_TASK_PATH`、`FFPROBE_PATH`、`AMATSUKAZE_HOST` / `AMATSUKAZE_PORT` / `AMATSUKAZE_DEFAULT_PROFILE` / `AMATSUKAZE_ATX_DIV_PROFILE`、`PROCESSOR_PREFLIGHT_SPACE_MULTIPLIER`) は変わらない。`processor-retry.*` と `processor-quality.*` の既定値は設定クラスにある。
- `processor:console` は `spring.profiles.include` に `processing` を含める。`preflight` は Spring なしに同じ設定ファイルを読む (`DiagnosticEnvironment.load`)。manager の 2 アプリは移行中だけ含める (上記)。

## トランザクション

core にあったときと同じ (module-boundaries.md §7.2)。

- `ProcessingJobClaimCommand.insert` / `retry`: `REQUIRES_NEW`。重複した INSERT のインデックスロックを解放してから retry の CAS を試すため、`ProcessingJobCommand.claim` からプロキシ越しに呼ぶ。重複しても呼び出し元のトランザクションを rollback-only にしない。
- `ProcessingJobCommand` の状態遷移 (`link`、`transition`、`acknowledgeSubmission`、`complete`、`claimCallback`、`releaseCallback`、`freezeManifest`、`reconcileSubmission`、`reset`、`prepareReset`): `REQUIRED` の短いトランザクション。外部の副作用をまたいでトランザクションを持たない。
- `ProcessingHistoryCommand`: 書き込みは `REQUIRES_NEW` の `TransactionTemplate`、読み取り (`history` / `status`) は `REQUIRES_NEW` + `readOnly` + `REPEATABLE_READ`。外部処理を呼ぶ前にトランザクションを終える。
- `RecordingQualityCommand.record` / `release`: `REQUIRED`。どちらも `processing_job` の行を `FOR UPDATE` でロックしてから判定する。

## 境界チェック

- `./gradlew processing:checkProcessingBoundaries` (`check` / `build` に含まれる): プロジェクト依存が `catalog`・`storage`・`diagnostics` (と test 設定からの `database-schema`) だけであること、processing のソースが他モジュール (core・library・manager・processor・console-common) のパッケージを参照しないこと、processing 以外のソースと資源 (main/test の Kotlin・Java・XML・YAML・properties) が `persistence` などの内部パッケージを参照しないこと、`persistence` のトップレベル宣言が `internal` (または `private`) であることを検査する。先に `checkProcessingBoundaryRules` が、違反を含むフィクスチャがすべて検出され、許可される参照が検出されないことを確かめる。catalog・storage・diagnostics の内部パッケージを参照しないことは、それぞれの境界チェックが検査する。
- `ProcessingStorageCallerTest`: storage の例外の再試行/中断の分類と、processing の呼び出し元の公開シグネチャに jcifs・Spring `Resource`・Web の型が出ないこと。

## テスト

`./gradlew processing:test`。DB テスト (`ProcessingJobMapperTest`、`ProcessingHistoryCommandTest`、`RecordingQualityCommandTest`) は Testcontainers で MariaDB を起動するため Docker が必要 (`DATABASE_CONNECTION` で接続先を上書きできる。初期化スクリプトは `database-schema` のテストフィクスチャ `ddl/01_create_database.sql`。保守用 SQL `db/maintenance/processing_history_cleanup.sql` も同じフィクスチャから読む)。テスト用の起動クラスは `TestApplication` (storage・catalog・diagnostics・processing をスキャンし、catalog と processing の Mapper を登録)、`Logger` は `TestLoggerConfig` が提供する。他モジュールのテストクラスやテスト用の補助には依存しない。`ProcessingConfigurationTest` は `processing` プロファイルの設定の既定値と、外部ツール・shell の Bean がその設定だけで組み立つことを確かめる。そのほかの単体テスト (再試行・中断・preflight・品質判定・外部ツール・shell・処理補助) は core にあったときのまま移した。
