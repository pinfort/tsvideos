# storage

NAS (録画の保存先) へのアクセスを提供するモジュール。#132 で `core` から抽出した。設計上の位置づけと契約は [module-boundaries.md](../docs/module-boundaries.md) の §8 を参照。

- **業務モジュールに依存しない。** プロジェクト依存を持たず、ソースは `me.pinfort.tsvideos.storage` 以外のパッケージを参照しない。claim・マニフェストの状態・`created_file` の登録・健全性の判定などの業務上の意味は呼び出し元 (`core` など) が持つ。
- 利用側は `implementation(project(":storage"))` に依存し、`me.pinfort.tsvideos.storage.api` の型だけを使う。jcifs を直接宣言・import しない。

## パッケージ

| パッケージ | 内容 |
| --- | --- |
| `me.pinfort.tsvideos.storage.api` (公開) | `NasStore` (保存値と同じ定数名 `VIDEO_STORE_NAS` / `ORIGINAL_STORE_NAS`)、例外 (`NasStorageException` / `NasAccessException` / `NasAuthenticationException`)、`NasComponent` (フォールバック付きの削除・検証付き移動・存在確認と、ストア指定の読み出しハンドル `NasReadHandle`)、`VerifiedUploadNas` (検証付きアップロードの基本操作。ストア明示、フォールバックなし)、`NasFileVerifier` (読み取り専用の存在/サイズ確認。低レベルの `Status`)、`NasPaths` (baseDir を含む保存パスの組み立て)、`DiagnosticNasClient` (doctor/preflight 用の短いタイムアウトの接続。`DiagnosticNasClient.create()` で Spring なしに作れる)、`sha256Hex` / `hex` |
| `me.pinfort.tsvideos.storage.samba` (internal) | Samba (jcifs) 実装、jcifs 例外から storage 例外への変換 (`SambaFailures`)、`samba.*` 設定 (`SambaConfigurationProperties`) とその登録 (`SambaStorageConfiguration`)。トップレベルの宣言はすべて `internal` |

## 組み立て

- 実行アプリは `scanBasePackages` に `me.pinfort.tsvideos.storage` を含める。`samba.*` の設定クラスは `SambaStorageConfiguration` が登録するので、`@ConfigurationPropertiesScan` の対象に加える必要はない。
- storage は外部の Bean を必要としない。ロガーも `LoggerFactory` で自前に作り、`core` の `LoggerConfig` (注入先ごとの `Logger` Bean) には依存しない。storage だけのスキャンと `storage` プロファイルで組み立つことを `StorageContractTest` が Spring Boot で確かめる。
- 既定値は [application-storage.yaml](src/main/resources/application-storage.yaml) にあり、`storage` プロファイルで読み込む。3 つの実行アプリ (`manager:api` の `application.yml`、`manager:console` / `processor:console` の `application.yaml`) は、それぞれ自分の設定で `spring.profiles.include` に `core` と `storage` を明示する (同名のクラスパス資源はどれか 1 つしか読まれないため、`core` の `application.yaml` に頼らない)。`core` の `application.yaml` も `core` のテスト用に `storage` を含める。設定キー (`samba.video-store-nas.*` / `samba.original-store-nas.*`) と環境変数 (`VIDEO_STORE_NAS_*` / `ORIGINAL_STORE_NAS_*`) は抽出前と同じ。
- `tvmcli doctor` と `tvpcli preflight` は Spring コンテキストを作らずに `DiagnosticNasClient.create()` で診断用接続を作る。設定は `DiagnosticEnvironment` が同じプロファイル (`core`, `storage`) から読む。

## 資源の寿命

抽出前の挙動をそのまま維持している (module-boundaries.md §13 U7)。

- `NasComponent`: 両ストアのルートを Bean の生成時に作って保持する。`openForRead` だけは呼び出しごとに新しいルートを作り、返したハンドルがそのルートを所有する。
- `VerifiedUploadNas`: 操作ごとにルートを開き、戻る前に閉じる。
- `NasFileVerifier`: セッション単位でルートを開き、セッションの終了時に閉じる。
- `DiagnosticNasClient`: 接続ごとに独立した短いタイムアウトのコンテキストを作り、接続の close で閉じる。

## 境界チェック

- `./gradlew storage:checkStorageBoundaries` (`check` に含まれる): storage がプロジェクト依存を持たないこと、storage のソースが他モジュールのパッケージを参照しないこと、storage 以外のソースが公開パッケージ `me.pinfort.tsvideos.storage.api` 以外の storage パッケージ (`samba` と今後の内部パッケージ) と jcifs を参照しないこと、storage 以外のビルドスクリプトが jcifs を宣言しないことを検査する。パッケージ名 `me.pinfort.tsvideos.storage` そのもの (コンポーネントスキャンの指定) は許可する。先に `checkStorageBoundaryRules` が、違反を含むフィクスチャ (逆方向のプロジェクト依存・ソース参照、外部からの内部パッケージ/jcifs の参照、jcifs の宣言) がすべて検出され、許可される参照が検出されないことを確かめる。
- `StorageContractTest`: 公開型のシグネチャに jcifs・Samba 実装・Spring `Resource`・HTTP の型が出ないこと、jcifs を import するのが Samba 実装だけであること、Samba 実装のトップレベル宣言が `internal` であること、パッケージのスキャンと既定の設定で公開契約の Bean がそろうこと。
- 呼び出し元の検査 (例外の再試行/中断の分類、`core` の公開シグネチャ) は `core` の `StorageCallerBoundaryTest` に置く。

## テスト

`./gradlew storage:test`。`NasMoveIntegrationTest` と `VerifiedUploadNasTest` は Testcontainers で Samba を起動するため Docker が必要。
