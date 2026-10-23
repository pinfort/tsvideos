# library

ライブラリ管理 (台帳の上に乗る運用者向けの機能) を扱うモジュール。#157 で `core` から最初の一群を、#158 で一括操作とクリーンアップを、#159 で健全性と検証を抽出した。設計上の位置づけと契約は [module-boundaries.md](../docs/module-boundaries.md) の §2、§5.2、§5.3、§7.2、§7.3、§7.5、§11.2 を参照。

現在 library にあるのは、番組検索 (手動コレクション/タグによる絞り込み)、統計、保存領域の概要、手動コレクション/タグ、重複検出と重複レビュー、一括操作 (`BulkLibraryCommand`、運用は [bulk-library.md](../docs/bulk-library.md))、クリーンアップ (`CleanupPlanCommand`)、健全性・検証 (`LibraryHealthCommand`、`LibraryHealthScanCommand`、`HealthVerificationStore`、`LibraryVerificationCommand`、運用は [library-health.md](../docs/library-health.md))。ライブラリ管理の機能はすべて library にある (旧 `core` は #139 で廃止した)。

- **依存してよいプロジェクトは `catalog` と `storage` だけ。** `diagnostics`・`processing`・`processor:*`・`manager:*` (と廃止した旧モジュール) には依存せず、ソースは `me.pinfort.tsvideos.library`・`catalog`・`storage` 以外のパッケージを参照しない。`database-schema` は DB テストのテストフィクスチャとしてだけ使う。
- NAS は storage の公開型 (`NasFileVerifier` など) だけで扱う。
- 台帳 (`program` など) は catalog の公開操作 (`ProgramCommand`、`ProgramInventory`、`CreatedFileCatalog`、`CatalogStatistics`、`LedgerSnapshots`、`LedgerLocks`) で読み、ロックする。一括操作の削除/移動も catalog の協調操作 (`ProgramCommand.delete` / `moveCreatedFiles`) を呼ぶ。library の SQL は台帳に書き込まず、ロックもかけない。台帳との JOIN は明示的な参照クエリ (`LibraryProgramSearchSql`、`LibraryOrganizationSql` の件数/番組ごとの一覧) だけ (module-boundaries.md §5.2)。
- 利用側 (`manager:*`) は `implementation(project(":library"))` に依存し、公開パッケージ `api` と `domain` の型だけを使う。Mapper・SQL には触れない。
- #157 で移行中だけ入れた `core` → `library` の依存は、#158 で `CleanupPlanCommand` が library に移ったので外した。`processor:console` は library に依存せず、実行時クラスパスにも入れない (`checkModuleGraph`、#139)。

## パッケージ

| パッケージ | 内容 |
| --- | --- |
| `me.pinfort.tsvideos.library.api` (公開) | 公開操作: `LibraryProgramSearchCommand`、`LibraryStatsCommand`、`StorageOverviewCommand`、`LibraryOrganizationCommand`、`DuplicateProgramCommand`、`DuplicateReviewCommand`、`BulkLibraryCommand`、`CleanupPlanCommand` (と例外 `CleanupResourceMissing`)、`LibraryVerificationCommand`、`LibraryHealthCommand`、`LibraryHealthScanCommand` (と例外 `HealthScanAlreadyRunning`)、`HealthVerificationStore`。Mapper の登録先 `LibraryPersistence.MAPPER_PACKAGE` |
| `me.pinfort.tsvideos.library.domain` (公開) | 手動コレクション/タグ (`LibraryKind`、`LibraryLabel`、`ProgramOrganization`、`LibraryProgramFilter`、例外 `LibraryItemNotFoundException` / `DuplicateLibraryNameException`)、統計 (`LibraryStatsQuery`、`LibraryStats`、`LibraryStatsGroup`、`LibraryStatsTotals`)、保存領域の概要 (`StorageOverview`、`StorageRecording`、`StorageDuplicateCandidate`)、重複 (`DuplicateGroup`、`DuplicateKeepCriterion`、`DuplicateRecommendation`、`DuplicateReview`、`DuplicateReviewGroup`、`DuplicateReviewMember`)、一括操作 (`BulkPlan` 一式: `BulkAction`、`BulkRequest`、`BulkApplyRequest`、`BulkSnapshot`、`BulkTarget`、`BulkOutcome`、`BulkOutcomeStatus`、`BulkResult`、例外 `InvalidBulkRequestException` / `BulkInterruptedException`)、クリーンアップ (`CleanupPlan` 一式: `CleanupMode`、`CleanupCriteria`、`CleanupGroup`、`CleanupTarget`、`CleanupFile`)、健全性 (`LibraryHealth`、`HealthProgram`、`HealthRun`、`HealthFinding`)、検証 (`VerificationReport`、`FileFinding`、`VerificationStatus`)。`BulkSnapshot` は `POST /api/v1/programs/bulk/preview` の JSON にそのまま出るので、プロパティ名・値・enum 名を変えない |
| `me.pinfort.tsvideos.library.persistence` (internal) | MyBatis の Mapper (`LibraryProgramSearchMapper`、`LibraryOrganizationMapper`、`LibraryHealthMapper`)、SQL プロバイダ (`LibraryProgramSearchSql`、`LibraryOrganizationSql`)、検索結果の行 `LibraryProgramRow`、健全性の行 `HealthRunDto` / `HealthFindingDto`、`GeneratedKeyHolder` (catalog と同じく module 内の複製)。トップレベルの宣言はすべて `internal` |

番組の型 (`Program`、`ProgramDetail`、`CreatedFile`、`ProgramSearchQuery` / `ProgramSearchPage`) は catalog の公開型のまま使い、API の JSON の形は変わらない。Mapper を引数に取る `LibraryProgramSearchCommand`、`LibraryOrganizationCommand`、`LibraryHealthCommand`、`HealthVerificationStore` のコンストラクタは `internal`。library の外では Spring の Bean として注入するか、テストではモックにする。`application` パッケージは内部サービスがないので作っていない。

## 組み立て

- library は DataSource・`PlatformTransactionManager`・`SqlSessionFactory` を定義しない。実行アプリは `scanBasePackages` に `me.pinfort.tsvideos.library` を含め、`@MapperScan` に `LibraryPersistence.MAPPER_PACKAGE` を加えて、library の Mapper も catalog と同じ、そのアプリの 1 組の `SqlSessionFactory` とトランザクションマネージャに登録する。`persistence` のパッケージ名は library の外に書かない。
- `manager:api` と `manager:console` は library を組み立てる。`processor:console` は #157 で移行中だけ library をスキャンしていたが、core → library の依存がなくなった #158 で外した。
- 設定ファイルとプロファイルを持たない。DB 接続の設定は実行アプリ (各アプリの `application-datasource.yaml`)、注入先ごとの `Logger` も実行アプリ (各アプリの `config/LoggerConfig`) のもの。

## トランザクション

core にあったときと同じ (module-boundaries.md §7.2)。

- `LibraryProgramSearchCommand.search`、`LibraryStatsCommand.summarize`、`StorageOverviewCommand.overview`、`DuplicateReviewCommand.review`、`LibraryOrganizationCommand.forProgram`: `REQUIRED` + `readOnly` + `REPEATABLE_READ`。catalog の読み取りはこの中に参加し、同じスナップショットで読む。
- `LibraryOrganizationCommand` の更新系 (`create` / `rename` / `delete` / `assign` / `assignTag`): `REQUIRED`。関連付けの変更は catalog の `LedgerLocks.lockProgramRow` (`MANDATORY`) で program を先にロックし、続けてラベルをロックする (program → label の順、§5.3)。
- `LibraryHealthCommand.read`: `REQUIRED` + `readOnly` + `REPEATABLE_READ`。findings と catalog の番組名を同じスナップショットで読む。
- `HealthVerificationStore` の全操作 (`start` / `complete` / `fail` / `failAbandoned`): `REQUIRES_NEW`。NAS の確認 (`LibraryVerificationCommand.verify`、storage の `NasFileVerifier` のセッション 1 つ) はトランザクションの外で行う。`LibraryHealthScanCommand.scan` は宣言なしで、実行中の run を 1 つに限る (`active_marker` の一意制約、重複は `HealthScanAlreadyRunning`)。
- `LibraryOrganizationCommand.list` / `find`、`DuplicateProgramCommand.detect`、`LibraryVerificationCommand.verify`: 宣言なし。
- `BulkLibraryCommand.preview`、`CleanupPlanCommand.plan` と dry-run の `apply`: `TransactionTemplate` で `readOnly` + `REPEATABLE_READ`。
- `BulkLibraryCommand.apply` (と `CleanupPlanCommand.apply` が使う `internal` の `applyGuarded`): 対象ごとに `REQUIRES_NEW` + `REPEATABLE_READ` のトランザクションを開き、対象をまたいでロックを持たない。ロックは catalog の `LedgerLocks` (`MANDATORY`) で `lockCreatedFiles` → (ガード付きのみ `lockWholeLedger` → ガード評価) → `lockProgramGraph` の順に取る。ロック下でスナップショットとフィンガープリントを作り直し、一致したときだけ catalog の `ProgramCommand.delete` / `moveCreatedFiles` (`REQUIRED`) で変更する (module-boundaries.md §7.3、§7.5)。

## 境界チェック

- `./gradlew library:checkLibraryBoundaries` (`check` / `build` に含まれる): プロジェクト依存が `catalog`・`storage` (と test 設定からの `database-schema`) だけであること、library のソースが他モジュールのパッケージを参照しないこと、library 以外のソースと資源 (main/test の Kotlin・Java・XML・YAML・properties) が `persistence` などの内部パッケージを参照しないこと、`persistence` のトップレベル宣言が `internal` であることを検査する。先に `checkLibraryBoundaryRules` が、違反を含むフィクスチャがすべて検出され、許可される参照が検出されないことを確かめる。catalog の内部パッケージを参照しないことは catalog の `checkCatalogBoundaries` が検査する。
- `LibraryBoundaryTest`: library の SQL が台帳テーブルに書き込まず、`FOR UPDATE` で台帳をロックしないこと、processing のテーブルを参照しないこと。

## テスト

`./gradlew library:test`。DB テスト (`LibraryOrganizationCommandTest`、`LibraryStatsCommandTest`、`StorageOverviewCommandTest`、`DuplicateReviewCommandTest`、`LibraryCatalogTransactionTest`、`BulkCatalogTransactionTest`、`BulkLibraryApplyTest`、`CleanupPlanApplyTest`、`LibraryHealthMapperTest`) は Testcontainers で MariaDB を起動するため Docker が必要 (`DATABASE_CONNECTION` で接続先を上書きできる。初期化スクリプトは `database-schema` のテストフィクスチャ `ddl/01_create_database.sql`)。テスト用の起動クラスは `TestApplication` (storage・catalog・library をスキャンし、catalog と library の Mapper を登録)、`Logger` は `TestLoggerConfig` が提供する。`LibraryCatalogTransactionTest` は library の読み取りが呼び出し元のスナップショットに参加すること、絞り込み検索が catalog の検索と結果・件数・並び順・ページングで一致することを確かめる。`BulkCatalogTransactionTest` は一括適用が catalog の `MANDATORY` のロックを対象ごとのトランザクションで取り、確定し、NAS 失敗で戻ることを、`BulkLibraryApplyTest` / `CleanupPlanApplyTest` は実際の DB と NAS のモックで STALE 判定・中断・部分失敗を確かめる (#158 で core から移した)。フィンガープリント・ロック順序・ガードの単体の検査は `BulkLibraryCommandTest`。`LibraryHealthMapperTest` は健全性の run/findings の保存、失敗時の FAILED 記録と前回の結果の保持、同時実行の排他、起動時の解放を、`LibraryHealthScanCommandTest` / `HealthVerificationStoreTest` / `LibraryVerificationCommandTest` は scan の失敗処理、理由の切り詰め、NAS の状態の対応付けを確かめる (#159 で core から移した)。
