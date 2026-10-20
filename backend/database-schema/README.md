# database-schema

DB スキーマの単一の情報源と、DB テスト用の資源を持つモジュール。#133 で `core` から抽出した。設計上の位置づけと契約は [module-boundaries.md](../docs/module-boundaries.md) の §11.6 を参照。

- **データ操作 API を持たない。** main とテストフィクスチャに Kotlin/Java のソースを置かず、プロジェクト依存も持たない。テーブルの読み書きは所有モジュール (module-boundaries.md §5.1) が提供する。
- **本番の依存先にならない。** 利用側は DB テストでだけ `testImplementation(testFixtures(project(":database-schema")))` を宣言する。testImplementation は推移しないので、DB テストを持つモジュールごとに宣言する (現在は `core` と `manager:api`)。

## 内容

| パス | 内容 |
| --- | --- |
| `src/main/resources/db/migrations/` | 番号付き migration (`NNN_name.sql`)。ファイル名順に適用し、すべて `IF NOT EXISTS` で再実行可能。運用者が既存 DB に手で適用する |
| `src/main/resources/db/maintenance/processing_history_cleanup.sql` | 処理履歴の明示的なクリーンアップ ([processing-history.md](../docs/processing-history.md))。migration とは別 |
| `src/testFixtures/resources/ddl/02_insert_data.sql` | シードデータ (`docker-compose.test.yml` が最後に `999_insert_data.sql` としてマウントする) |
| `ddl/01_create_database.sql` (生成物、テストフィクスチャ) | `generateTestDdl` が migration をファイル名順に連結して生成する Testcontainers の初期化スクリプト (`TC_INITSCRIPT=ddl/01_create_database.sql`) |

migration を追加するときは新しい番号のファイルを追加し、リポジトリルートの `docker-compose.test.yml` にマウントを 1 行足す。適用済みの migration は書き換えない。

## 境界チェック

- `./gradlew database-schema:checkDatabaseSchemaBoundaries` (`check` に含まれる): プロジェクト依存とソースがないこと、他のプロジェクトがテスト用の設定からテストフィクスチャとしてだけ参照すること、他のモジュールに migration と DB テスト用 DDL がないこと、migration の名前と番号の重複、`docker-compose.test.yml` のマウントが migration のファイル名順とシードデータに一致することを検査する。先に `checkDatabaseSchemaBoundaryRules` が、違反を含むフィクスチャがすべて検出されることを確かめる。
- `checkDatabaseSchemaRuntimeIsolation` ([runtime-isolation.gradle.kts](runtime-isolation.gradle.kts)。実行アプリ `manager:api`、`manager:console`、`processor:console` が適用し、各アプリの `check` に含まれる): 解決後の `runtimeClasspath` / `productionRuntimeClasspath` に database-schema とそのテストフィクスチャが入っていないことを、依存グラフと成果物で検査する。設定の継承 (`extendsFrom`) で入った場合も検出する。新しい実行アプリを追加したら同じ `apply(from = ...)` を書く (書き忘れは `checkDatabaseSchemaBoundaries` が検出する)。
- `DatabaseSchemaResourcesTest` (`./gradlew database-schema:test`): 生成 DDL が migration のファイル名順の連結と一致すること、各資源がテストフィクスチャのクラスパスから読めること。Docker は不要。
