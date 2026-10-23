# library

ライブラリ管理 (台帳の上に乗る運用者向けの機能) を扱うモジュール。#157 で `core` から最初の一群を抽出した。設計上の位置づけと契約は [module-boundaries.md](../docs/module-boundaries.md) の §2、§5.2、§5.3、§7.2、§11.2 を参照。

現在 library にあるのは、番組検索 (手動コレクション/タグによる絞り込み)、統計、保存領域の概要、手動コレクション/タグ、重複検出と重複レビュー。一括操作 (`BulkLibraryCommand`)、クリーンアップ (`CleanupPlanCommand`)、健全性・検証 (`LibraryHealthCommand`、`LibraryVerificationCommand` など) はまだ `core` にあり、後続の Issue (#158、#159) で移す。

- **依存してよいプロジェクトは `catalog` と `storage` だけ。** `core`・`diagnostics`・`processor:*`・`manager:*` には依存せず、ソースは `me.pinfort.tsvideos.library`・`catalog`・`storage` 以外のパッケージを参照しない。`database-schema` は DB テストのテストフィクスチャとしてだけ使う。
- 台帳 (`program` など) は catalog の公開操作 (`ProgramCommand`、`ProgramInventory`、`CreatedFileCatalog`、`CatalogStatistics`、`LedgerLocks`) で読み、ロックする。library の SQL は台帳に書き込まず、ロックもかけない。台帳との JOIN は明示的な参照クエリ (`LibraryProgramSearchSql`、`LibraryOrganizationSql` の件数/番組ごとの一覧) だけ (module-boundaries.md §5.2)。
- 利用側 (`manager:*`、移行中は `core` も) は `implementation(project(":library"))` に依存し、公開パッケージ `api` と `domain` の型だけを使う。Mapper・SQL には触れない。
- `core` → `library` の依存は移行中だけのもの (core に残る `CleanupPlanCommand` が library の公開操作を使う)。逆向きの `library` → `core` は禁止。

## パッケージ

| パッケージ | 内容 |
| --- | --- |
| `me.pinfort.tsvideos.library.api` (公開) | 公開操作: `LibraryProgramSearchCommand`、`LibraryStatsCommand`、`StorageOverviewCommand`、`LibraryOrganizationCommand`、`DuplicateProgramCommand`、`DuplicateReviewCommand`。Mapper の登録先 `LibraryPersistence.MAPPER_PACKAGE` |
| `me.pinfort.tsvideos.library.domain` (公開) | 手動コレクション/タグ (`LibraryKind`、`LibraryLabel`、`ProgramOrganization`、`LibraryProgramFilter`、例外 `LibraryItemNotFoundException` / `DuplicateLibraryNameException`)、統計 (`LibraryStatsQuery`、`LibraryStats`、`LibraryStatsGroup`、`LibraryStatsTotals`)、保存領域の概要 (`StorageOverview`、`StorageRecording`、`StorageDuplicateCandidate`)、重複 (`DuplicateGroup`、`DuplicateKeepCriterion`、`DuplicateRecommendation`、`DuplicateReview`、`DuplicateReviewGroup`、`DuplicateReviewMember`) |
| `me.pinfort.tsvideos.library.persistence` (internal) | MyBatis の Mapper (`LibraryProgramSearchMapper`、`LibraryOrganizationMapper`)、SQL プロバイダ (`LibraryProgramSearchSql`、`LibraryOrganizationSql`)、検索結果の行 `LibraryProgramRow`、`GeneratedKeyHolder` (catalog と同じく module 内の複製)。トップレベルの宣言はすべて `internal` |

番組の型 (`Program`、`ProgramDetail`、`CreatedFile`、`ProgramSearchQuery` / `ProgramSearchPage`) は catalog の公開型のまま使い、API の JSON の形は変わらない。Mapper を引数に取る `LibraryProgramSearchCommand` と `LibraryOrganizationCommand` のコンストラクタは `internal`。library の外では Spring の Bean として注入するか、テストではモックにする。`application` パッケージは内部サービスがないので作っていない。

## 組み立て

- library は DataSource・`PlatformTransactionManager`・`SqlSessionFactory` を定義しない。実行アプリは `scanBasePackages` に `me.pinfort.tsvideos.library` を含め、`@MapperScan` に `LibraryPersistence.MAPPER_PACKAGE` を加えて、library の Mapper も catalog・core と同じ 1 組の `SqlSessionFactory` とトランザクションマネージャに登録する。`persistence` のパッケージ名は library の外に書かない。
- `manager:api` と `manager:console` は library を組み立てる。`processor:console` も移行中だけ library をスキャンする (core 全体をスキャンしており、core に残る library の呼び出し元が library の Bean を要求するため)。core → library の依存がなくなったら外す。
- 設定ファイルとプロファイルを持たない。DB 接続の設定は実行アプリ (現状は `core` の `application-core.yaml`) のもの。

## トランザクション

core にあったときと同じ (module-boundaries.md §7.2)。

- `LibraryProgramSearchCommand.search`、`LibraryStatsCommand.summarize`、`StorageOverviewCommand.overview`、`DuplicateReviewCommand.review`、`LibraryOrganizationCommand.forProgram`: `REQUIRED` + `readOnly` + `REPEATABLE_READ`。catalog の読み取りはこの中に参加し、同じスナップショットで読む。
- `LibraryOrganizationCommand` の更新系 (`create` / `rename` / `delete` / `assign` / `assignTag`): `REQUIRED`。関連付けの変更は catalog の `LedgerLocks.lockProgramRow` (`MANDATORY`) で program を先にロックし、続けてラベルをロックする (program → label の順、§5.3)。
- `LibraryOrganizationCommand.list` / `find`、`DuplicateProgramCommand.detect`: 宣言なし。

## 境界チェック

- `./gradlew library:checkLibraryBoundaries` (`check` / `build` に含まれる): プロジェクト依存が `catalog`・`storage` (と test 設定からの `database-schema`) だけであること、library のソースが他モジュールのパッケージを参照しないこと、library 以外のソースと資源 (main/test の Kotlin・Java・XML・YAML・properties) が `persistence` などの内部パッケージを参照しないこと、`persistence` のトップレベル宣言が `internal` であることを検査する。先に `checkLibraryBoundaryRules` が、違反を含むフィクスチャがすべて検出され、許可される参照が検出されないことを確かめる。catalog の内部パッケージを参照しないことは catalog の `checkCatalogBoundaries` が検査する。
- `LibraryBoundaryTest`: library の SQL が台帳テーブルに書き込まず、`FOR UPDATE` で台帳をロックしないこと、processing のテーブルを参照しないこと。

## テスト

`./gradlew library:test`。DB テスト (`LibraryOrganizationCommandTest`、`LibraryStatsCommandTest`、`StorageOverviewCommandTest`、`DuplicateReviewCommandTest`、`LibraryCatalogTransactionTest`) は Testcontainers で MariaDB を起動するため Docker が必要 (`DATABASE_CONNECTION` で接続先を上書きできる。初期化スクリプトは `database-schema` のテストフィクスチャ `ddl/01_create_database.sql`)。テスト用の起動クラスは `TestApplication` (storage・catalog・library をスキャンし、catalog と library の Mapper を登録)、`Logger` は `TestLoggerConfig` が提供する。`LibraryCatalogTransactionTest` は library の読み取りが呼び出し元のスナップショットに参加すること、絞り込み検索が catalog の検索と結果・件数・並び順・ページングで一致することを確かめる。core に残る一括適用の検査は core の `BulkCatalogTransactionTest` にある。
