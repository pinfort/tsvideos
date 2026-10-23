# catalog

録画・番組・分割ファイル・作成ファイルの台帳 (`executed_file` / `program` / `splitted_file` / `created_file`) と検出タグ/実施記録 (`executed_file_tag` / `executed_file_check`) を扱うモジュール。#135 で `core` から抽出した。設計上の位置づけと契約は [module-boundaries.md](../docs/module-boundaries.md) の §2、§5、§6、§7、§11.1 を参照。

- **依存してよいプロジェクトは `storage` だけ。** `core`・`processor:*`・`manager:*` には依存せず、ソースは `me.pinfort.tsvideos.catalog` と `me.pinfort.tsvideos.storage` 以外のパッケージを参照しない。`database-schema` は DB テストのテストフィクスチャとしてだけ使う。
- 利用側 (`core`、`manager:*`、`processor:*`) は `implementation(project(":catalog"))` に依存し、公開パッケージ `api` と `domain` の型だけを使う。Mapper・DTO・SQL には触れない。
- catalog の SQL は library/processing のテーブルを参照しない。手動コレクション/タグとの JOIN は library 側の明示的な参照クエリ (`LibraryProgramSearchMapper` など、module-boundaries.md §5.2) が持つ。

## パッケージ

| パッケージ | 内容 |
| --- | --- |
| `me.pinfort.tsvideos.catalog.api` (公開) | 公開操作: `ProgramCommand`、`CreatedFileCommand`、`ExecutedFileCommand`、`SplittedFileCommand`、`ExecutedFileTagCommand`、`CreatedFileCatalog`、`ProgramInventory`、`LedgerSnapshots`、`LedgerLocks`、`CatalogStatistics`。移動先パスの規則 `DirectoryNameComponent` / `NormalizeComponent`。結果型 `ProgramSummary`、`ProgramSnapshot`、`CreatedFileSnapshot`、`ProgramStatsRow`、`FileStatsRow`、`StorageReference`、`StreamableVideo`。Mapper の登録先 `CatalogPersistence.MAPPER_PACKAGE` |
| `me.pinfort.tsvideos.catalog.domain` (公開) | `Program`、`ProgramDetail`、`ExecutedFile`、`SplittedFile`、`CreatedFile`、`VideoResolution`、検出タグ/実施記録の名前 (`ExecutedFileTag` / `ExecutedFileCheck`)、検索条件と結果 (`ProgramSearchQuery` / `ProgramSearchPage`) |
| `me.pinfort.tsvideos.catalog.persistence` (internal) | MyBatis の Mapper (アノテーションと SQL プロバイダ `ProgramSearchSql`。XML はない)、DTO、`GeneratedKeyHolder`。トップレベルの宣言はすべて `internal` |

公開操作のコンストラクタは `internal` (引数が internal の Mapper のため)。catalog の外では Spring の Bean として注入するか、テストではモックにする。`application` パッケージは内部サービスがないので作っていない。

## 組み立て

- catalog は DataSource・`PlatformTransactionManager`・`SqlSessionFactory` を定義しない。3 つの実行アプリは `scanBasePackages` に `me.pinfort.tsvideos.catalog` を含め、`@MapperScan` に `CatalogPersistence.MAPPER_PACKAGE` を (manager では `LibraryPersistence.MAPPER_PACKAGE`、processor では `ProcessingPersistence.MAPPER_PACKAGE` と並べて) 加え、catalog の Mapper も自分の 1 組の `SqlSessionFactory` とトランザクションマネージャに登録する。`persistence` のパッケージ名は catalog の外に書かない。
- 注入先ごとの slf4j `Logger` は実行アプリ (現状は `core` の `LoggerConfig`) が提供する。catalog は `Logger` 型の注入だけを要求する。
- 設定ファイルとプロファイルを持たない。DB 接続の設定は実行アプリ (現状は `core` の `application-core.yaml`) のもの。
- NAS は storage の公開操作 (`NasComponent`) を使う。協調削除/移動は「DB の行を変更してから NAS を変更する」順序を保つ (module-boundaries.md §7.4)。

## トランザクション

core にあったときと同じ (module-boundaries.md §7.2)。

- `ProgramCommand.search`: `readOnly` + `REPEATABLE_READ`。
- `ProgramCommand.delete` / `moveCreatedFiles`、`CreatedFileCommand.delete` / `move`、`ExecutedFileTagCommand.recordCheck`: `REQUIRED`。
- `LedgerLocks` の全操作、`CreatedFileCatalog.register` / `fillMimeIfMissing` / `fillResolutionIfMissing`: `MANDATORY`。
- `ProgramInventory`、`LedgerSnapshots`、`CatalogStatistics`、`CreatedFileCatalog` の読み取り: 宣言なし (呼び出し元のトランザクションに参加)。
- 宣言のない登録・状態更新 (`ProgramCommand.insert` など) は、トランザクション外で呼ばれれば文単位で確定する。
- ロックの順序と 10 秒の待機上限は `LedgerLocks` が守る (§7.3)。

## 境界チェック

- `./gradlew catalog:checkCatalogBoundaries` (`check` / `build` に含まれる): プロジェクト依存が `storage` (と test 設定からの `database-schema`) だけであること、catalog のソースが他モジュールのパッケージを参照しないこと、catalog 以外のソースと資源 (main/test の Kotlin・Java・XML・YAML・properties) が `persistence` などの内部パッケージを参照しないこと、`persistence` のトップレベル宣言が `internal` であることを検査する。先に `checkCatalogBoundaryRules` が、違反を含むフィクスチャがすべて検出され、許可される参照が検出されないことを確かめる。
- `CatalogBoundaryTest`: catalog の SQL が library/processing のテーブルを参照しないこと、`ProgramSearchQuery` に library の条件がないこと。
- `CatalogStorageCallerTest` (#165 で core の `StorageCallerBoundaryTest` から移した): 作成ファイル操作 (`CreatedFileCommand`、`CreatedFileCatalog`、`StreamableVideo`) の公開シグネチャに jcifs・Spring `Resource`・Web の型が出ないこと、catalog のソースが jcifs を import しないこと。

## テスト

`./gradlew catalog:test`。Mapper と公開操作の DB テスト (`*MapperTest`、`CreatedFileCatalogTest`、`CatalogTransactionTest`) は Testcontainers で MariaDB を起動するため Docker が必要 (`DATABASE_CONNECTION` で接続先を上書きできる。初期化スクリプトは `database-schema` のテストフィクスチャ `ddl/01_create_database.sql`)。テスト用の起動クラスは `TestApplication` (storage と catalog だけをスキャン)、`Logger` は `TestLoggerConfig` が提供する。library の呼び出し元と組み合わせた検査は library の `LibraryCatalogTransactionTest` と、一括適用の `BulkCatalogTransactionTest` (#158 で core から library に移した) にある。
