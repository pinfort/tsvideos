# processing

録画の処理 (processor 側) の業務を扱うモジュール。#164 で `core` から処理専用のコードのうち、ジョブ所有権・処理履歴・録画品質・再試行・中断・preflight・外部ツール/shell・処理補助を抽出し、#165 で検証付きアップロード (`VerifiedUploadCommand`、`UploadManifest` / `VerifiedUploadReceipt`、`UploadManifestMapper`) を移した。#166 で旧 `processor:infrastructure` (pipeline・reset・after-encode・tsselect によるドロップ検査・TS 解析による緊急警報放送/文字スーパー検出・Slack 通知) を統合し、そのモジュールを削除した。設計上の位置づけと契約は [module-boundaries.md](../docs/module-boundaries.md) の §2、§4、§7.6、§11.3、§11.7 を参照。

現在 processing にないもの:

- `ApplicationVersion`、`LoggerConfig`、DataSource の設定 (`application-core.yaml`) は #139 まで `core` に残る。

依存の規則:

- **依存してよいプロジェクトは `catalog`・`storage`・`diagnostics` だけ。** `core`・`library`・`console-common`・`processor:*`・`manager:*` には依存せず、ソースは `me.pinfort.tsvideos.processing`・`catalog`・`storage`・`diagnostics` 以外のパッケージを参照しない。`database-schema` は DB テストのテストフィクスチャとしてだけ使う。
- 台帳は catalog の公開操作、NAS は storage の公開型、DB/NAS の環境診断は diagnostics の公開操作だけで扱う。
- 利用側は `implementation(project(":processing"))` に依存し、公開パッケージ `api` (下位の `api.config` / `api.shell` / `api.tool` を含む) と `domain` の型だけを使う。Mapper には触れない。
- **利用するのは processor 側だけ**: #165 で `core` → `processing` の移行中の依存をなくした。manager の 2 アプリ (`manager:api`、`manager:console`) は processing に依存せず、スキャン・Mapper 登録・`processing` プロファイルの include もしない。実行時クラスパスに processing が入らないことは各アプリの `checkProcessingRuntimeIsolation` ([runtime-isolation.gradle.kts](runtime-isolation.gradle.kts)) が検査する。`processing` → `core` は禁止。

## パッケージ

| パッケージ | 内容 |
| --- | --- |
| `me.pinfort.tsvideos.processing.api` (公開) | pipeline の入口 (`processor:console` が使う): `PathProcessingRunner` (`process`。結果は `PathProcessingRunner.Summary`)、`AfterEncodeRunner` (`after-encode`。入力は `AfterEncodeRunner.Input`)、`ResetRunner` (`reset`)。公開操作: `ProcessingJobCommand`、`ProcessingJobClaimCommand`、`ProcessingHistoryCommand`、`RecordingQualityCommand`、`ProcessingPreflightCommand`、`VerifiedUploadCommand` (検証付きアップロードと復旧操作 `claimed` / `releaseAbandonedClaim` / `cleanupTemporary`)。補助部品: `ProcessingCancellation`、`ProcessingRetry` 一式 (`ProcessingRetryRuntime`、`RetryOperation`、`RetryRefusedException`、`TransientProcessingFailure`)、`RecordingQualityInspector`、`LocalPreflightProbe` / `EncoderConnectivityProbe`、`CompressComponent`、`MainSplittedFileFinderComponent`、`NasDestinationResolver`、`MimeTypeComponent`、`ValidateCompletedComponent`、`RunningPlatformComponent`、`expandRecordingInputs`。Mapper の登録先 `ProcessingPersistence.MAPPER_PACKAGE` |
| `me.pinfort.tsvideos.processing.api.config` (公開) | 設定クラス `ProcessorToolConfigurationProperties` (`processor-tool.*`)、`ProcessingRetryConfigurationProperties` (`processor-retry.*`)、`RecordingQualityConfigurationProperties` (`processor-quality.*`) |
| `me.pinfort.tsvideos.processing.api.shell` (公開) | `ShellClient`、`ShellComponent`、`ShellResult` |
| `me.pinfort.tsvideos.processing.api.tool` (公開) | `TsSplitterClient`、`AmatsukazeAddTaskClient`、`DurationProbeClient`、`RecordingStreamProbeClient`、`VideoResolutionProbeClient` |
| `me.pinfort.tsvideos.processing.domain` (公開) | ジョブ (`ProcessingJob`、`ProcessingIdentity`)、履歴 (`ProcessingHistory.kt` の `ProcessingAttempt`、`ProcessingStageEvent`、`ProcessingStatus`、`ProcessingErrorCode` など)、品質 (`RecordingQuality.kt` の `QualityAction`、`RecordingQualityPolicy`、`RecordingQualityEvaluator` など、`RecordingQualityReport`)、アップロード (`UploadManifest`、`VerifiedUploadReceipt`)、`FileName`、例外 (`TsVideosException`、`InvalidFileNameException`、`InvalidEnvironmentException`、`ShellCommandTimeoutException`、`QualityReleaseRefusedException`) |
| `me.pinfort.tsvideos.processing.pipeline` (internal) | 4 段の `FileProcessingPipeline` (ドロップ検査 → TsSplitter → 圧縮 + 検証付きアップロード → Amatsukaze 投入。アップロード開始前の段階だけを逆順にロールバックする) と `RollbackRunner` |
| `me.pinfort.tsvideos.processing.external.tsselect` (internal) | `TsSelectClient` (`me.pinfort:tsselect` の tsdump モードで JVM 内でドロップ数を数える) |
| `me.pinfort.tsvideos.processing.external.ts` (internal) | `EmergencyBroadcastDetector` (PAT/PMT と文字スーパー PES を解析し、catalog の検出タグ `EWS` / `SUPERIMPOSE` を返す)、`AribCaptionData`、`TsUtil` (`Crc32Mpeg2`) |
| `me.pinfort.tsvideos.processing.external.slack` (internal) | `SlackClient` (`SLACK_WEBHOOK_URL`。未設定なら通知しない) |
| `me.pinfort.tsvideos.processing.persistence` (internal) | MyBatis の Mapper (`ProcessingJobMapper`、`ProcessingHistoryMapper`、`RecordingQualityMapper`、`UploadManifestMapper`) と `GeneratedKeyHolder` (catalog・library と同じく module 内の複製)。トップレベルの宣言はすべて `internal` (または `private`) |

外部ツール・shell・処理補助は #164 で `api` の下に公開したまま残している (#166 で pipeline が processing に入った後も、パッケージは変えていない)。`processor:console` が使うのは pipeline の入口 3 つと既存の公開操作だけで、pipeline の内部・tsselect・TS 解析・Slack は internal にした。そのため内部型を引数に取る `PathProcessingRunner` (`FileProcessingPipeline`・`SlackClient`) と `AfterEncodeRunner` (`SlackClient`) のコンストラクタは `internal` で、`ResetRunner` は公開コンストラクタのまま。Mapper を引数に取る `ProcessingJobCommand`、`ProcessingJobClaimCommand`、`ProcessingHistoryCommand`、`RecordingQualityCommand`、`VerifiedUploadCommand` のコンストラクタは `internal`。processing の外では Spring の Bean として注入するか、テストではモックにする。`ProcessingPreflightCommand`・`LocalPreflightProbe`・`EncoderConnectivityProbe` は公開コンストラクタのままで、`processor:console` の `PreflightBootstrap` が Spring コンテキストなしに組み立てる。`ProcessingJobCommand.reconcileSubmission` など本番の呼び出し元がない操作も残している (module-boundaries.md §13 U4)。`application` パッケージは内部サービスがないので作っていない。

## 組み立て

- processing は DataSource・`PlatformTransactionManager`・`SqlSessionFactory`・`Logger` を定義しない。実行アプリは `scanBasePackages` に `me.pinfort.tsvideos.processing` を、`@MapperScan` に `ProcessingPersistence.MAPPER_PACKAGE` を、`@ConfigurationPropertiesScan` に `me.pinfort.tsvideos.processing.api.config` を加えて、processing の Mapper も catalog と同じ 1 組の `SqlSessionFactory` とトランザクションマネージャに登録する。`persistence` のパッケージ名は processing の外に書かない。
- 設定は [application-processing.yaml](src/main/resources/application-processing.yaml) (`processing` プロファイル) に `processor-tool.*` と `processor-preflight.space-multiplier` が入る。#164 で `core` の `application-core.yaml` から移した。キー・既定値・環境変数 (`TS_SPLITTER_PATH`、`AMATSUKAZE_ADD_TASK_PATH`、`FFPROBE_PATH`、`AMATSUKAZE_HOST` / `AMATSUKAZE_PORT` / `AMATSUKAZE_DEFAULT_PROFILE` / `AMATSUKAZE_ATX_DIV_PROFILE`、`PROCESSOR_PREFLIGHT_SPACE_MULTIPLIER`) は変わらない。`processor-retry.*` と `processor-quality.*` の既定値は設定クラスにある。`processor.upload.verify-sha256` (既定 `false`) は #165 で core から移った `VerifiedUploadCommand` の `@Value` が読む。
- `processor:console` は `spring.profiles.include` に `processing` を含める (#166 で中身が空だった旧 `processor:infrastructure` の `infrastructure` プロファイル (`application-infrastructure.yaml`) を削除し、include からも外した)。`SLACK_WEBHOOK_URL` は `SlackClient` の `@Value` が既定値なし (空) で読む。`preflight` は Spring なしに同じ設定ファイルを読む (`DiagnosticEnvironment.load`)。manager の 2 アプリは含めない (#165)。

## トランザクション

core にあったときと同じ (module-boundaries.md §7.2)。

- `ProcessingJobClaimCommand.insert` / `retry`: `REQUIRES_NEW`。重複した INSERT のインデックスロックを解放してから retry の CAS を試すため、`ProcessingJobCommand.claim` からプロキシ越しに呼ぶ。重複しても呼び出し元のトランザクションを rollback-only にしない。
- `ProcessingJobCommand` の状態遷移 (`link`、`transition`、`acknowledgeSubmission`、`complete`、`claimCallback`、`releaseCallback`、`freezeManifest`、`reconcileSubmission`、`reset`、`prepareReset`): `REQUIRED` の短いトランザクション。外部の副作用をまたいでトランザクションを持たない。
- `ProcessingHistoryCommand`: 書き込みは `REQUIRES_NEW` の `TransactionTemplate`、読み取り (`history` / `status`) は `REQUIRES_NEW` + `readOnly` + `REPEATABLE_READ`。外部処理を呼ぶ前にトランザクションを終える。
- `RecordingQualityCommand.record` / `release`: `REQUIRED`。どちらも `processing_job` の行を `FOR UPDATE` でロックしてから判定する。
- `VerifiedUploadCommand`: クラスは `NOT_SUPPORTED`、マニフェストの状態遷移・claim・登録は内部の `TransactionTemplate` で `REQUIRES_NEW`。NAS 操作中に DB トランザクションを持たず、catalog の `CreatedFileCatalog.register` (`MANDATORY`) はマニフェストの `REGISTERED` 遷移と同じトランザクションで commit する (module-boundaries.md §7.6、[verified-uploads.md](../docs/verified-uploads.md))。

## 境界チェック

- `./gradlew processing:checkProcessingBoundaries` (`check` / `build` に含まれる): プロジェクト依存が `catalog`・`storage`・`diagnostics` (と test 設定からの `database-schema`) だけであること、processing のソースが他モジュール (core・library・manager・processor・console-common) のパッケージを参照しないこと、processing 以外のソースと資源 (main/test の Kotlin・Java・XML・YAML・properties) が `persistence` などの内部パッケージを参照しないこと、`persistence` のトップレベル宣言が `internal` (または `private`) であること、manager の 2 アプリが `runtime-isolation.gradle.kts` を適用していることを検査する。先に `checkProcessingBoundaryRules` が、違反を含むフィクスチャがすべて検出され、許可される参照が検出されないことを確かめる。catalog・storage・diagnostics の内部パッケージを参照しないことは、それぞれの境界チェックが検査する。
- `ProcessingStorageCallerTest`: storage の例外の再試行/中断の分類と、processing の呼び出し元 (#165 から `VerifiedUploadCommand`・`UploadManifest`・`VerifiedUploadReceipt` を含む) の公開シグネチャに jcifs・Spring `Resource`・Web の型が出ないこと。
- `checkProcessingRuntimeIsolation` ([runtime-isolation.gradle.kts](runtime-isolation.gradle.kts)、manager の 2 アプリが適用し `check` に含まれる): manager の解決後の実行時クラスパス (`runtimeClasspath` / `productionRuntimeClasspath`) に processing が入らないこと。`manager:api` の `ApiApplicationTest` は、`processing` プロファイルと `processor-*` 設定なしでコンテキストが起動し、processing のクラスが読み込めず、processing の Bean がないことを確かめる。

## テスト

`./gradlew processing:test`。DB テスト (`ProcessingJobMapperTest`、`ProcessingHistoryCommandTest`、`RecordingQualityCommandTest`、`VerifiedUploadCommandTest`) は Testcontainers で MariaDB を起動するため Docker が必要 (`DATABASE_CONNECTION` で接続先を上書きできる。初期化スクリプトは `database-schema` のテストフィクスチャ `ddl/01_create_database.sql`。保守用 SQL `db/maintenance/processing_history_cleanup.sql` も同じフィクスチャから読む)。テスト用の起動クラスは `TestApplication` (storage・catalog・diagnostics・processing をスキャンし、catalog と processing の Mapper を登録)、`Logger` は `TestLoggerConfig` が提供する。他モジュールのテストクラスやテスト用の補助には依存しない。`ProcessingConfigurationTest` は `processing` プロファイルの設定の既定値と、外部ツール・shell の Bean がその設定だけで組み立つことを確かめる。そのほかの単体テスト (再試行・中断・preflight・品質判定・外部ツール・shell・処理補助) は core にあったときのまま移した。`VerifiedUploadCommandTest` も #165 で core から内容を変えずに移した (Samba コンテナを使う SMB の結合テストを含むので Docker が必要)。#166 で旧 `processor:infrastructure` の単体テスト (`FileProcessingPipelineTest`、`RollbackRunnerTest`、`PathProcessingRunnerTest`、`AfterEncodeRunnerTest`、`ResetRunnerTest`、`TsSelectClientTest`、`EmergencyBroadcastDetectorTest`、`SlackClientTest` と補助の `historyFixture`) を、パッケージ名と import だけを変えて移した。内部の型を使うテストも processing に置く。
