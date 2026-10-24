# Kotlin コードレビュー (#152)

バックエンドの Kotlin コードを「Kotlin らしさ」の観点で棚卸しした記録。ここでいう Kotlin らしさは、null・状態・可変性の意図が型とコードから読み取れ、制御フローを追いやすいこととする。短く書くことや構文の統一そのものは目的にしない。

- 対象: `storage`、`catalog`、`diagnostics`、`library`、`processing`、`console-common`、`manager:api`、`manager:console`、`processor:console` の `src/main/kotlin`。関連テストと Gradle 設定は、契約と制約を確認するためだけに参照した。`database-schema` にはアプリケーション Kotlin がないため対象外 (SQL の書き換えもしない)。
- 基準コード: `f2743c1` (2026-10-24 時点の `origin/main`) に、本 Issue の変更 ([§4](#4-本-issue-で実施した変更)) を加えたもの。文中の行番号はこの状態のもの。
- 範囲外: システム構造の説明は #153 / [module-boundaries.md](module-boundaries.md) の担当。この文書はコードレビューと改善判断に限る。

## 1. 判断基準

### 1.1 観点

| 観点 | 確認したこと |
| --- | --- |
| null 安全性 | nullable 型、safe call、Elvis、不要な `!!`・`checkNotNull`、Java 境界 (Spring `TransactionTemplate.execute`、MyBatis、jcifs、`System.getenv` など) の戻り値の扱い |
| 可変性 | 不要な `var` や可変コレクションと、フレームワーク・状態管理・復旧記録のために必要な可変性の区別 |
| コレクション・制御フロー | `any` / `all` / `firstOrNull` などで意図が明確になる箇所。評価順、短絡評価、重複、例外、副作用を保てるか |
| 型・モデル | 文字列の状態値、センチネル値、data class / enum / sealed 型を使えそうな箇所。永続化・JSON・公開 API 互換性への影響 |
| 関数の読みやすさ | スコープ関数の入れ子、非ローカル return、名前の shadowing、長い関数。単純なループや明示的な分岐のほうが読みやすければ維持する |
| 例外・リソース管理 | `use` / `runCatching` / `try-finally` の使い方。close 順、suppressed 例外、中断 (interrupt) の伝播、トランザクションの rollback 条件 |

### 1.2 採用・維持・保留の基準

- **採用 (本 Issue で実施)**: 外部仕様 (API・CLI・JSON・DB 保存値)、mapper の呼び出し回数と順序、副作用、例外、モジュール境界のどれも変えずに、意図の読みやすさが上がるもの。既存テストの期待値を変えずに検証できること。
- **後続 Issue 候補**: 改善の利益がはっきりしているが、次のいずれかに当たるもの。複数ファイル・複数モジュールに及ぶ、テストの期待値 (mock の呼び出し列など) を書き換える必要がある、挙動の一部 (例外型、rollback 条件、出力) が変わりうる。
- **既存 Issue へ整理**: 既存の Issue (#104 / #105 / #106 / #108 / #112) の範囲に入るもの。ここでは重複実装せず、現状のコードとの差分だけを記録する ([§3](#3-既存-issue-との照合))。
- **変更不要**: 一見すると問題に見えるが、フレームワークの要求、状態管理・復旧の記録、性能、永続化形式などの理由で現状が適切なもの。理由を残して維持する。

### 1.3 機械的に判定しないもの

- 検索のヒット数は品質の指標にしない。`!!` は main のコードに演算子としては存在しない。唯一のヒットは `NormalizeComponent.kt:19` の文字列リテラル `"‼" to "!!"` (全角記号の正規化表) で、問題ではない。
- テストの `lateinit var` (例: `ProgramCommandTest` の mock 保持) は Kotest の `beforeTest` で毎回初期化するための慣用であり、対象にしない。main には `lateinit` がない。
- `var` は理由を確認してから判定した。MyBatis の生成キー受け取り (`GeneratedKeyHolder.id`)、ロールバック用の状態フラグ、`@Volatile` のフラグ、バイト列パーサの位置カウンタは、いずれも必要な可変性とした。
- 全ループのコレクション関数化や、根拠のない `Sequence` 化はしない。

## 2. モジュール別の確認範囲と結果

各モジュールの「主に読んだファイル」は、[§1.1](#11-観点) の観点で内容を読んで判断したファイル。それ以外のファイル (値型や薄い宣言だけのファイル、例: `*Dto.kt`、例外クラス、`LoggerConfig.kt`) は全行を精読しておらず、観点に関わる構文 (`!!`、`lateinit`、`var`、`runCatching`、`throw Exception` など) の検索で該当した箇所だけを確認した。したがって「候補なし」「変更不要」は、この確認範囲での結果であり、モジュール内に改善点が一切無いことを保証するものではない。

### 2.1 storage

- 主に読んだファイル: `storage/samba/SambaNasComponent.kt`、`SambaReadHandle.kt`、`SambaNasFileVerifier.kt`、`SambaVerifiedUploadNas.kt`、`SambaDiagnosticNasClient.kt`、`SambaClient.kt`、`SambaFailures.kt`、`SambaConfigurationProperties.kt`、`storage/api/*.kt`
- 結果: 変更不要が中心。後続候補 1 件 ([F4](#f4-checked-な-exception-の送出を個別に見直す-低))。

| 観点 | 判断 |
| --- | --- |
| null 安全性 | storage 内の nullable は意味のある「無い」に限られる。`moveFirstFound` (`SambaNasComponent.kt:111-115`) はどのストアにも見つからない場合を `NasStore?` の null で返し、呼び出し元の `move` (`:105-108`) が null を判定して `IllegalStateException` にする。`NasFileVerifier.Result` (`NasFileVerifier.kt:28-32`) の `actualBytes` / `reason` は状態によって値が無いため nullable で、型がその契約を表している。`SambaReadHandle.close` の `failure: Exception?` (`SambaReadHandle.kt:52-60`) は最初の失敗の記録。いずれも維持。なお `sambaOperation` (`SambaFailures.kt:33`) は jcifs の例外を storage の例外に翻訳するだけで、戻り値の null を検査するものではない。jcifs のプラットフォーム型の戻り値 1 つずつの null 可能性は、本レビューでは個別に確認していない |
| 可変性 | `SambaReadHandle.streams` / `closed` (`SambaReadHandle.kt:18-19`) は開いたストリームの追跡と二重 close 防止のため `synchronized` 下で必要。`moveFirstFound` の `var created` (`SambaNasComponent.kt:125`) は排他作成に成功したかどうかの記録で、後片付けの条件になる。いずれも維持 |
| 制御フロー | `moveFirstFound` (`SambaNasComponent.kt:111-159`) の `return@use` と、`use` の中からの非ローカル `return type` は、「最初に見つかったストア」を返す明示的なループとして読める。`firstNotNullOfOrNull` などに置き換えると、`use` による close の範囲が読み取りにくくなるため維持 |
| 型・モデル | `NasStore` は永続化名を固定した enum (`NasStore.kt`)。`NasFileVerifier.Status` は呼び出し側が自分の型に写像する低レベル状態で、適切 |
| 例外・リソース | `use` の入れ子 (`SambaVerifiedUploadNas.kt:41-118`) は root → resource → stream の close 順を構文で保証しており、維持。`SambaReadHandle.close` (`SambaReadHandle.kt:39-61`) は全 closeable を閉じて最初の失敗に残りを suppressed として付ける。`SambaNasFileVerifier.Session` の `runCatching` (`SambaNasFileVerifier.kt:24`) は `InterruptedException` を明示的に再送出しており、中断は伝播する。`discardCopy` の `runCatching` (`SambaNasComponent.kt:185,190`) は失敗した move の後片付けで、失敗を元の例外より優先させないための意図的な握りつぶし。`getResource` / `deleteResource` の `throw Exception(...)` (`SambaNasComponent.kt:58,86`) は [F4](#f4-checked-な-exception-の送出を個別に見直す-低) |

補足: `SambaConfigurationProperties` は `password` を含む data class で、自動生成の `toString()` に資格情報が出る。現時点で `toString()` やログ出力に使う経路は見つからなかったため変更不要とするが、この型をログに出す変更をするときは注意が必要。

### 2.2 catalog

- 主に読んだファイル: `catalog/api/ProgramCommand.kt`、`CreatedFileCommand.kt`、`CreatedFileCatalog.kt`、`ExecutedFileCommand.kt`、`ExecutedFileTagCommand.kt`、`SplittedFileCommand.kt`、`LedgerLocks.kt`、`LedgerSnapshots.kt`、`ProgramInventory.kt`、`DirectoryNameComponent.kt`、`catalog/domain/*.kt`、`catalog/persistence/ProgramDto.kt`、`CreatedFileDto.kt`、`ProgramSearchSql.kt`、`GeneratedKeyHolder.kt`
- 結果: 本 Issue で 1 件実施 ([§4](#4-本-issue-で実施した変更))。後続候補 3 件 ([F2](#f2-台帳の欠損値を表すセンチネル値を整理する-低)、[F3](#f3-programdto-の既定値の重複をなくす-低)、[F4](#f4-checked-な-exception-の送出を個別に見直す-低))。

| 観点 | 判断 |
| --- | --- |
| null 安全性 | `ProgramCommand.find` / `findByName` などは nullable を返して safe call で変換する (`ProgramCommand.kt:48-52`)。`LedgerLocks.lockProgramGraph` (`LedgerLocks.kt:49-56`) も Elvis で早期 return する。適切なので維持。一方、`ProgramDto.toDomain` (`ProgramDto.kt:33-46`) は LEFT JOIN で欠けた録画列を `-1` / `0` / `LocalDateTime.MIN` / `""` / `-1.0` に置き換え、null の意味が型から消えている ([F2](#f2-台帳の欠損値を表すセンチネル値を整理する-低)) |
| 可変性 | `GeneratedKeyHolder.var id` (`persistence/GeneratedKeyHolder.kt:5`) は MyBatis が生成キーを書き込むための受け皿で、`val` にはできない。`moveCreatedFiles` の `mutableListOf` (`ProgramCommand.kt:160`) は移動済み NAS ファイルの復旧記録で、失敗時に `restoreMoved` へ渡す順序そのものが契約。可変性をなくすより副作用と復旧順序の明確さを優先し、維持。`ProgramSearchSql.fromWhere` の `conditions` (`ProgramSearchSql.kt:32`) は条件を順に積むビルダーで、固定の SQL 断片だけを連結する安全性の説明もしやすい。維持 |
| 制御フロー | `hasTsFile` の `forEach` 内非ローカル return を `any` に置き換えた ([§4](#4-本-issue-で実施した変更))。他のループは副作用 (削除・移動) の順序を表しており、`forEach` のままが適切 |
| 型・モデル | DTO と domain の enum を `when` で 1 対 1 に写像している (`CreatedFileDto.kt:30-35`、`ProgramDto.kt:26-31`)。網羅的な `when` なので、片方に値を足すとコンパイルエラーで気付ける。維持。検出タグ (`ExecutedFileTag`) が enum ではなく文字列定数なのは、DB スキーマを変えずに検出器を追加できる設計 (`ExecutedFileTag.kt:8`) のため維持。`CreatedFile.isMp4` / `isTs` はプロパティなので JSON に出る (`ProgramDetailResponse` が domain をそのまま返す)。domain のプロパティを変えると API 応答が変わる点に注意 |
| 関数の読みやすさ | `ProgramDto.toDomain` と `toProgramDetail` で既定値の列挙が重複している ([F3](#f3-programdto-の既定値の重複をなくす-低)) |
| 例外・リソース | `CreatedFileCommand.delete` / `move` は「RuntimeException にしないとロールバック対象にならない」というコメント付きで包み直している (`CreatedFileCommand.kt:52-56,90-97`)。Spring の `@Transactional` は checked 例外では rollback しないためで、意図は正しい。同じ理由で、`ProgramCommand.insert` / `delete` の `throw Exception(...)` (`ProgramCommand.kt:74,131`) は扱いが紛らわしい ([F4](#f4-checked-な-exception-の送出を個別に見直す-低)) |

### 2.3 diagnostics

- 主に読んだファイル: `diagnostics/api/EnvironmentDiagnosticCommand.kt`、`NasDiagnosticProbe.kt`、`DatabaseDiagnosticProbe.kt`、`DiagnosticCancellation.kt`、`DiagnosticEnvironment.kt`、`diagnostics/domain/DiagnosticReport.kt`
- 結果: 候補なし (変更不要)。

| 観点 | 判断 |
| --- | --- |
| null 安全性 | 設定値は `String?` のまま検証し、プローブに渡す直前で null でないことをスマートキャストで確定している (`EnvironmentDiagnosticCommand.kt:36,71`)。適切 |
| 可変性 | `checks` (`EnvironmentDiagnosticCommand.kt:18`、`NasDiagnosticProbe.kt:22`) は、途中で例外になっても、それまでの診断結果を報告に残すための蓄積。`writeProbe` の `var created` (`NasDiagnosticProbe.kt:81`) は排他作成で所有権を得たかどうかで、片付けの条件になる。いずれも維持 |
| 例外・リソース | `setting` の `runCatching { ... }.getOrNull()` (`EnvironmentDiagnosticCommand.kt:82`) は、プレースホルダ解決の失敗でも doctor が診断を続けるための意図的な握りつぶしで、値が無い扱いになる。`validUri` (`:109`) も同じ。`diagnosticCancellation` (`DiagnosticCancellation.kt:4`) は、渡された例外の cause 連鎖とスレッドの割り込みフラグから中断を復元するが、`setting` が握りつぶした例外は `diagnosticCancellation(error)` に渡らない。そのため `getProperty` が割り込みフラグを立てずに `InterruptedException` (または cause に持つ例外) を投げた場合、その中断は値なしとして扱われて失われる。フラグが立っていれば、次の `diagnosticCancellation()` 呼び出し (プローブ後と `diagnose` の最後、`:39,78`) で検出される。YAML や環境変数などの通常の property source の解決では `InterruptedException` は想定しにくいため、変更不要とした (この境界は [§6](#6-変更不要とした主な項目) にも記録)。`use` の入れ子 (`DatabaseDiagnosticProbe.kt:35-41`) で connection → statement → result の close 順を保証している |

`diagnosticCancellation` と同じ「cause 連鎖に `InterruptedException` があるか」の判定は `ProcessingCancellation.isInterrupted` (processing) や `BulkLibraryCommand` (library) にもある。これらのモジュール間には依存がなく、共通化のためだけに共有モジュールを作らない方針 ([module-boundaries.md](module-boundaries.md)) なので、重複は維持する。

### 2.4 library

- 主に読んだファイル: `library/api/BulkLibraryCommand.kt`、`CleanupPlanCommand.kt`、`DuplicateProgramCommand.kt`、`DuplicateReviewCommand.kt`、`StorageOverviewCommand.kt`、`LibraryHealthCommand.kt`、`HealthVerificationStore.kt`、`LibraryVerificationCommand.kt`、`LibraryOrganizationCommand.kt`、`LibraryProgramSearchCommand.kt`、`LibraryStatsCommand.kt`、`library/domain/*.kt`
- 結果: 後続候補 1 件 ([F5](#f5-名前の-shadowing-と不要な-nullable-を解消する-低))。センチネル値に依存する箇所は [F2](#f2-台帳の欠損値を表すセンチネル値を整理する-低) に含めた。

| 観点 | 判断 |
| --- | --- |
| null 安全性 | `BulkLibraryCommand.target` (`BulkLibraryCommand.kt:268-309`) は欠けた snapshot を `?.` と `orEmpty()` で扱い、理由の `when` で null を最初に判定する。読みやすく維持。`DuplicateProgramCommand.group` (`DuplicateProgramCommand.kt:62,89,95`) は `recordedAt != LocalDateTime.MIN`、`duration <= 0` などのセンチネル値で欠損を判定しており、catalog の型の問題が下流に表れている ([F2](#f2-台帳の欠損値を表すセンチネル値を整理する-低)) |
| 可変性 | `applyGuarded` の `successful` / `outcomes` (`BulkLibraryCommand.kt:88-89`) は対象ごとに積む結果で、中断時に途中までの結果を例外に載せる。`applyOne` の `var mutating` (`:118`) は「変更を始めたか」で失敗メッセージを分ける。`DuplicateProgramCommand` の union-find (`:63-77`) は配列とループで書くのが自然。`StorageOverviewCommand.overview` の `var bytes` / `excluded` (`StorageOverviewCommand.kt:43-53`) は 1 回の走査で 2 つの値を数える単純なループで、2 回の `sumOf` / `count` に分けるより読みやすい。いずれも維持 |
| 型・モデル | `CleanupPlan.action: String = "DELETE"`、`CleanupFile.status: String` (`domain/CleanupPlan.kt:16,38`) は、オペレーターが確認して保存する JSON 計画ファイルの形式 (`schemaVersion` 付き)。enum にすると JSON の互換性を考える必要が出るため、検証 (`CleanupPlanCommand.kt:228`) で守る現状を維持 |
| 関数の読みやすさ | `LibraryOrganizationCommand.create` / `assignTag` (`LibraryOrganizationCommand.kt:53-55,114-118`) で、ローカル変数 `key` (生成キーの受け皿) と private 関数 `key(name)` が同名。`LibraryVerificationCommand.verify` (`LibraryVerificationCommand.kt:34,40`) でローカル `programs` がプロパティ `programs` を隠し、`this.programs` と書き分けている ([F5](#f5-名前の-shadowing-と不要な-nullable-を解消する-低))。`BulkLibraryCommand.fingerprint` の `encode(item: Any?)` (`:328-350`) は、フィールドを明示的に列挙してシリアライズするための意図的な動的分岐で、維持 |
| 例外・リソース | `applyOne` の catch (`:157-171`) は、`TransactionTemplate` や `CreatedFileCommand` が包み直した例外の cause 連鎖から中断を復元する。`LibraryHealthScanCommand.scan` (`LibraryHealthCommand.kt:59-77`) は `Exception` と `Error` を分けて扱い、`Error` は再送出する。いずれも意図どおりで維持 |

### 2.5 processing

- 主に読んだファイル: `processing/pipeline/FileProcessingPipeline.kt`、`RollbackRunner.kt`、`processing/api/AfterEncodeRunner.kt`、`VerifiedUploadCommand.kt`、`ProcessingJobCommand.kt`、`ProcessingJobClaimCommand.kt`、`ProcessingRetry.kt`、`ProcessingCancellation.kt`、`PathProcessingRunner.kt`、`ResetRunner.kt`、`RecordingQualityCommand.kt`、`MimeTypeComponent.kt`、`ValidateCompletedComponent.kt`、`MainSplittedFileFinderComponent.kt`、`shell/ShellClient.kt`、`tool/DurationProbeClient.kt`、`tool/VideoResolutionProbeClient.kt`、`external/slack/SlackClient.kt`、`processing/domain/*.kt`、`processing/persistence/ProcessingJobMapper.kt`、`RecordingQualityMapper.kt`
- 結果: 既存 Issue へ整理 4 件 (#104 / #105 / #106 / #108、[§3](#3-既存-issue-との照合))。後続候補 3 件 ([F1](#f1-mime-と-encoding-の文字列を定数に集約する-中)、[F6](#f6-afterencoderunnerrun-の-dry-run-と実行の分岐を整理する-低)、[F8](#f8-slackclient-の-json-生成を-jackson-にする-低))。duration のセンチネル値は [F2](#f2-台帳の欠損値を表すセンチネル値を整理する-低) に含めた。

| 観点 | 判断 |
| --- | --- |
| null 安全性 | `TransactionTemplate.execute` は Java 由来で `T?` を返すため、`checkNotNull(transaction.execute { ... })` や `== 1` / `!= true` の比較で扱っている (`VerifiedUploadCommand.kt:47,280,297`)。Java 境界の扱いとして適切。`AfterEncodeRunner.run` では dry-run かどうかが `job` / `token` / `attempt` の null で表され、`checkNotNull(job)` が 4 か所に散っている ([F6](#f6-afterencoderunnerrun-の-dry-run-と実行の分岐を整理する-低)) |
| 可変性 | `FileProcessingPipeline.processFile` の `var registered` / `splitInvoked` / `createdArchive` (`FileProcessingPipeline.kt:172,181,194`) は、ロールバック関数が「どこまで進んだか」を知るためにクロージャで共有する状態。`uploadStarted` / `submitted` (`:137-138`) は catch でのリトライ可否判定に使う。`RollbackRunner.rolledBackCleanly` (`RollbackRunner.kt:10`) は外から読めて書けない (`private set`)。`VerifiedUploadCommand.Claimed.var manifest` (`VerifiedUploadCommand.kt:43`) は CAS 遷移が成功したときだけ更新する、手元の状態の写し。`ShellClient.run` の `var stdout` / `stderr` / `exitedNormally` (`ShellClient.kt:51-53`) は `finally` での後片付けに必要。`ProcessingCancellation` の `@Volatile stopping` と `owner` (`ProcessingCancellation.kt:10-13`) はスレッド間の停止要求。いずれも維持 |
| 制御フロー | `ProcessingRetry.execute` の `while (true)` (`ProcessingRetry.kt:82-121`) はバックオフ付き再試行で、関数化しても読みやすくならない。`AfterEncodeRunner.run` の `return@forEach` (`AfterEncodeRunner.kt:133`) は dry-run で次のファイルへ進むためのもので、ループ内の continue として読める |
| 型・モデル | `UploadManifest.State` は enum とその集合 (`VERIFIED` / `AMBIGUOUS` / `OWNED_TEMPORARY`、`UploadManifest.kt:25-39`) で状態機械を表しており、良い例。履歴 (`ProcessingHistory.kt`) と品質 (`RecordingQuality.kt`) の状態も enum。これに対し `ProcessingJob.state` は `String` (`ProcessingJob.kt:40`) で、#104 の対象。MIME・encoding の文字列リテラルが複数ファイルに分散している ([F1](#f1-mime-と-encoding-の文字列を定数に集約する-中))。`DurationProbeClient.probe` は失敗を `0.0` で返す (`DurationProbeClient.kt:41-51`) のに対し、`VideoResolutionProbeClient.probe` は `null` で返す (`VideoResolutionProbeClient.kt:44-54`)。0.0 は `executed_file.duration` に保存され、下流が `> 0` で判定する ([F2](#f2-台帳の欠損値を表すセンチネル値を整理する-低)) |
| 関数の読みやすさ | `FileProcessingPipeline.processFile` (`:84-241`) は #106、`AfterEncodeRunner.run` (`:69-211`) は [F6](#f6-afterencoderunnerrun-の-dry-run-と実行の分岐を整理する-低)。`VerifiedUploadCommand.registerOnce` (`:253-314`) も長いが、REGISTERED の再実行時の同一性検査と補完の不変条件がひとまとまりで書かれており、分割すると検査の順序が追いにくくなるため維持 |
| 例外・リソース | `withClaim` (`VerifiedUploadCommand.kt:454-490`) は `Throwable` を捕捉して claim を解放し、解放の失敗を suppressed に付けてから元の例外を再送出する。`runCatching` で `Error` も対象にしているのは、claim を残さないための意図的な選択。`RollbackRunner.stage` (`RollbackRunner.kt:13-33`) は中断時にロールバックせず証拠を残す。`FileProcessingPipeline` の catch が `Exception` だけを対象にしている点 (`:218`) は #108 の項目 5。`SlackClient.toJson` の手書きエスケープ (`SlackClient.kt:52-61`) は [F8](#f8-slackclient-の-json-生成を-jackson-にする-低) |

### 2.6 console-common

- 主に読んだファイル: `console/common/component/UserQuestionComponent.kt`、`TerminalTextColorComponent.kt`、`charset/ConsoleCharset.kt`、`version/ApplicationVersion.kt`
- 結果: 後続候補 1 件 ([F5](#f5-名前の-shadowing-と不要な-nullable-を解消する-低) に含めた)。

| 観点 | 判断 |
| --- | --- |
| null 安全性 | `ConsoleCharset.resolveLogCharset` (`ConsoleCharset.kt:22-35`) は環境変数の null と空白を `?.trim().orEmpty()` でまとめて扱う。`ApplicationVersion.read` (`ApplicationVersion.kt:24-29`) はリソースが無い場合を Elvis で空の `Properties` にし、`use` で閉じる。いずれも適切。`TerminalTextColorComponent.decorateString(colorCode: Int?)` (`TerminalTextColorComponent.kt:28-36`) は、呼び出し元がすべて非 null を渡しており、nullable と null 分岐が不要 ([F5](#f5-名前の-shadowing-と不要な-nullable-を解消する-低)) |
| その他 | `UserQuestionComponent` は `readlnOrNull()` の null を EOF (= 既定の回答) として扱う。維持 |

### 2.7 manager:api

- 主に読んだファイル: `manager/api/controller/*.kt`、`stream/ResponseResources.kt`、`stream/NasVideoResource.kt`、`response/*.kt`、`runner/HealthRunStartupReconciler.kt`
- 結果: 後続候補 1 件 ([F7](#f7-programcontrollerget-の重複した読み取りをまとめる-低))。MIME リテラルは [F1](#f1-mime-と-encoding-の文字列を定数に集約する-中) に含めた。

| 観点 | 判断 |
| --- | --- |
| null 安全性 | `VideoFileController.stream` (`VideoFileController.kt:31`) は、見つからない場合を Elvis で 404 にして早期 return する。`ResponseResources.of` (`ResponseResources.kt:61-62`) は `as?` と `checkNotNull` で、フィルタが入っていない設定ミスを明示的な失敗にする。適切 |
| 可変性 | `ResponseResources.adopted` / `closed` (`:18-19`) は、レスポンスの後まで生きるリソースの所有記録で、`synchronized` で守られている。`ResponseResourcesFilter` の `var deferred` (`:90`) は非同期処理に引き継いだかどうかで、`finally` の条件になる。維持 |
| 関数の読みやすさ | コントローラはロジックを持たず、例外を HTTP ステータスに写像するだけ (`BulkLibraryController.validated`、`LibraryOrganizationController.respond`)。`ProgramController.get` だけは同じ番組を 3 回読む ([F7](#f7-programcontrollerget-の重複した読み取りをまとめる-低)) |
| 例外・リソース | NAS のハンドルは `ResponseResources` が所有し、本文を書き終えた後に閉じる (`VideoFileController.kt:32-40`)。close の責任が明示されており、維持 |

### 2.8 manager:console

- 主に読んだファイル: `manager/console/ConsoleRunner.kt`、`DoctorBootstrap.kt`、`commands/*.kt` (Search、Get、Delete、Modify、Duplicates、Stats、Verify、Cleanup、Doctor、ReadCommand、TsVideosManager)、`component/*.kt`
- 結果: 後続候補 1 件 ([F9](#f9-両-cli-の引数解析ヘルパーを-console-common-に寄せる-低))。

| 観点 | 判断 |
| --- | --- |
| null 安全性 | Clikt のオプションは nullable のまま `ProgramSearchQuery` に渡し、検証はドメイン側の `require` が担う (`Search.kt:63-82`)。`Delete` / `Modify` の `?: return println(...)` (`Delete.kt:47,60`、`Modify.kt:36`) は「表示して終了」を 1 行で書く慣用で、維持 |
| 制御フロー | `Search.read` は行ごとに `hasTsFile` を呼ぶ (`Search.kt:89,104`)。N+1 であることはコード内の TODO (`Search.kt:84`) に記録済みで、検索 SQL の変更にあたるため、Kotlin のコード改善の範囲外として維持 |
| 関数の読みやすさ | `parseManager` (`ConsoleRunner.kt:27-33`) は processor の `parseProcessor` と同じ処理 ([F9](#f9-両-cli-の引数解析ヘルパーを-console-common-に寄せる-低)) |

### 2.9 processor:console

- 主に読んだファイル: `processor/console/ConsoleRunner.kt`、`ProcessorShutdown.kt`、`PreflightBootstrap.kt`、`commands/*.kt` (ProcessCommand、AfterEncodeCommand、ResetCommand、ReleaseClaimCommand、HistoryCommand、QualityCommand、TsVideosProcessor)、`display/ProgressPrinter.kt`
- 結果: 後続候補 1 件 ([F9](#f9-両-cli-の引数解析ヘルパーを-console-common-に寄せる-低)、manager:console と共通)。

| 観点 | 判断 |
| --- | --- |
| 可変性 | `ProcessCommand.run` の `var summary` (`ProcessCommand.kt:33`) は、不変の `Summary` を `plus` 演算子で畳み込む書き方で、適切。`ProgressPrinter` の `private var` (`ProgressPrinter.kt:6-12`) は表示の重複を避けるための描画状態で、必要 |
| 例外・リソース | `ConsoleRunner.run` (`ConsoleRunner.kt:26-44`) は `InterruptedException`、`CliktError`、その他の例外で終了コードを分け、shutdown hook を残す条件もコメントで説明している。`HistoryReadCommand.run` (`HistoryCommand.kt:23-37`) は stdout の JSON に原因を混ぜない。いずれも維持 |
| 関数の読みやすさ | `parseProcessor` (`ConsoleRunner.kt:56-62`) は [F9](#f9-両-cli-の引数解析ヘルパーを-console-common-に寄せる-低) |

### 2.10 Gradle 設定の確認

- `libs.versions.toml` に Kotlin と ktlint (`org.jlleitschuh.gradle.ktlint` 14.2.0) の設定がある。Issue 作成時の調査では「catalog が `-Xjsr305=strict` を設定済み」とされていたが、現在は対象 9 モジュールと `database-schema` のすべての `build.gradle.kts` が `-Xjsr305=strict` を設定している。Spring / MyBatis などの JSR-305 注釈付き Java API は、全モジュールで null 許容性が Kotlin の型に反映される。
- 新しい解析ツール (Detekt など) は導入しない (Issue の範囲外)。

## 3. 既存 Issue との照合

「状態」列は 2026-10-24 に GitHub で取得した時点のもの ([#104](https://github.com/pinfort/tsvideos/issues/104)、[#105](https://github.com/pinfort/tsvideos/issues/105)、[#106](https://github.com/pinfort/tsvideos/issues/106)、[#108](https://github.com/pinfort/tsvideos/issues/108)、[#112](https://github.com/pinfort/tsvideos/issues/112)、[#153](https://github.com/pinfort/tsvideos/issues/153))。最新の状態は各リンク先で確認する。いずれも本 Issue の前提条件ではなく、ここでは重複実装しない。

| Issue | 状態 | 現在のコードでの該当箇所 | Issue 作成時からの差分 |
| --- | --- | --- | --- |
| #104 processing_job の状態を enum にする | OPEN | `ProcessingJob.state: String` (`processing/domain/ProcessingJob.kt:40`)。状態の文字列リテラルは `ProcessingJobCommand.kt:60-78,112,120`、`FileProcessingPipeline.kt:113-122,210,249-252,266`、`AfterEncodeRunner.kt:110`、`RecordingQualityCommand.kt:37,46,66,73`、`ResetRunner.kt:81` と、各 mapper の SQL にある。`allowedTransitions` は今もメソッドの後に宣言されている (`ProcessingJobCommand.kt:75-79`) | コードは `core` から `processing` へ移った (#164)。`QUARANTINED` 状態が追加され (recording quality)、`CLAIMED → QUARANTINED` と `QUARANTINED → RETRYABLE` は `RecordingQualityMapper.transition` を通るため、`allowedTransitions` の検査を経由しない。状態名は `ProcessingStatus.jobState: String` (`ProcessingHistory.kt:74`) として `tvpcli status --json` に出るので、enum にしても JSON の値 (名前) を変えないこと |
| #105 ProcessingJobMapper の SQL を整理する | OPEN | 同じ列リスト (`id,identity_key,...,updated_at`) が `ProcessingJobMapper` の `find` / `findByExecutedFile` / `findByPath` (`ProcessingJobMapper.kt:23,28,95`)、`RecordingQualityMapper.lockJob` (`RecordingQualityMapper.kt:14`)、`ProcessingHistoryMapper` (`ProcessingHistoryMapper.kt:136`) にある。`reconcile` の冗長な条件 (`state=#{expected}` と `state IN (...)` の併用) は残っている (`ProcessingJobMapper.kt:71`) | パッケージが `core/external/database` から `processing/persistence` に変わった。Issue の「claim helpers」は `ProcessingJobClaimCommand` になり、独自の SELECT は持たず `mapper.find` を使う。一方で列リストの重複は 3 mapper・5 か所に広がっている |
| #106 processFile の guard を抽出する | OPEN | `FileProcessingPipeline.processFile` の claim 前の guard 群 (`FileProcessingPipeline.kt:112-134`) | guard の内容と順序が変わった。現在は ① `find` → QUARANTINED なら即 return、② RETRYABLE 以外なら SUBMITTED/COMPLETED は skip・それ以外は失敗、③ `findByPath`、④ `findByFile`、⑤ 番組名の重複 (claim 前の事前チェックとして追加)、⑥ `claim`。inspection とファイルの同一性の再計算は claim の後、`INSPECT` 履歴ステージの中 (`:142-156`) に移った。抽出の受け入れ条件 (`FileProcessingPipelineTest` を期待値を変えずに通す) はそのまま使える |
| #108 アップロード後の rollback と job の不変条件 | OPEN | 項目ごとに下の表を参照 | — |
| #112 一括適用時の created_file ロック範囲 | OPEN | `LedgerLocks.lockCreatedFiles` (`catalog/api/LedgerLocks.kt:33-34`) → catalog 内部の `BulkLibraryMapper.lockFiles`。`BulkLibraryCommand.applyOne` が対象ごとに取得する (`library/api/BulkLibraryCommand.kt:122`)。プレビューは `LedgerSnapshots.allCreatedFiles` で全件を読む (`:65`) | ロックの所在が `core` の mapper 直呼びから catalog の公開操作 `LedgerLocks` に移った (#134/#158)。Issue が挙げる writer は、現在は `CreatedFileCatalog.register` (`VerifiedUploadCommand.registerOnce` から呼ばれる) と `CreatedFileCommand.move` (`updateFile`)。テーブル全体をロックする範囲そのものは変わっていない。Kotlin のコード改善ではないため、本文書では扱わない |
| #153 アーキテクチャ文書 | OPEN | — | 本文書はコードレビューと改善判断に限り、構造の説明は #153 と [module-boundaries.md](module-boundaries.md) に任せる |

#108 の各項目:

| 項目 | 現状 |
| --- | --- |
| 1. アップロード開始後の rollback で created_file 行が消える | 現在の `saveArchive` (`FileProcessingPipeline.kt:206`) は `RollbackRunner.stage` の外にあり、アップロード開始後に失敗しても DB の行はロールバックされない。catch は `uploadStarted` が true ならリトライ不可として扱う (`:232`)。コード上は解消済みに見える。Issue を閉じる前に、行が残ることを確かめるテストがあるか確認が必要 |
| 2. `executed_file_id` の一意性が強制されていない | 未解消。`064_processing_job.sql:15` は通常の `KEY` のままで、`findByExecutedFile` は `ORDER BY id DESC LIMIT 1` で重複を隠す (`ProcessingJobMapper.kt:28`) |
| 3. `.gz` が残っているとアップロードが飛ばされる | 挙動が変わった。`compressArchive` (`FileProcessingPipeline.kt:422-435`) は既存の `.gz` を `compress(..., false, ...)` の false 戻り値で検出し、「Compressed file already exists; reconcile before processing」で失敗する。黙ってスキップすることはない |
| 4. claim の前に inspection が走る | 挙動が変わった。claim (`:133`) の後に inspection (`:142-156`) が走るようになった。代わりに、claim の前には軽い DB 照会と番組名の重複チェックだけが残る |
| 5. `Exception` だけを捕捉している | 未解消。`processFile` の catch は `Exception` のまま (`:218`) |
| 6. ブロックされたジョブごとの runbook | 一部対応。[processing-idempotency.md](processing-idempotency.md) に「オペレーターの手順」節がある。`blockedJobMessage` (`FileProcessingPipeline.kt:246-256`) のすべての文言に対応する手順があるかは、本 Issue では確認していない |

## 4. 本 Issue で実施した変更

### `ProgramCommand.hasTsFile` を `any` で書く

- ファイル・シンボル: `catalog/src/main/kotlin/me/pinfort/tsvideos/catalog/api/ProgramCommand.kt` の `ProgramCommand.hasTsFile` (111-114 行目)
- 現状の問題: `forEach` の中から関数ごと `return true` する非ローカル return で「1 つでもあるか」を表していた。ラムダの中の `return` がラムダではなく関数から抜けることを知らないと、制御フローを読み違えやすい。
- 改善: `createdFileMapper.selectByExecutedFileId(program.executedFileId).any { it.toDomain().isTs }`。
- 利益: 存在判定という意図が関数名 (`any`) で読める。式本体になり、早期 return が無くなる。
- 挙動を保つ根拠: mapper の呼び出しは 1 回で引数も同じ。`any` は先頭から順に評価し、最初に true になった要素で止まる (短絡評価)。この点は以前の `forEach` + `return true` と同じ。TS の判定は引き続き `CreatedFile.isTs` (MIME が `video/vnd.dlna.mpeg-tts` と等しいか) で、MIME が null なら false。空リストは false。公開シグネチャ `fun hasTsFile(program: Program): Boolean` は変えていない。
- 検証: `ProgramCommandTest` の `hasTsFile` コンテキストにある既存の 3 ケース (TS あり、TS なし、空リスト) は期待値を変えずに残した。次の 3 ケースを追加した。要素の読み取りを記録するリスト (`ReadTrackingList`) で、どこで走査が止まったかを確かめる。
  - TS が先頭: true を返し、読んだのは要素 0 だけ (短絡評価)。mapper は `program.executedFileId` で 1 回だけ呼ばれる。
  - TS が途中: true を返し、要素 0、1 を順に読んで止まる (評価順と短絡評価)。mapper は 1 回。
  - MIME が null だけ: false を返し、全要素を読む。mapper は 1 回。

これ以外のアプリケーションコードは変更していない。

## 5. 後続 Issue 候補

以下はすべて対応先が「後続 Issue 候補」(未起票) のもの。本 Issue では実装しない。優先度は、壊れたときの影響 (実行時にしか分からない不一致か) と、変更の局所性で決めた。**中**: 実行時にしか分からない不一致を生みうるもの。**低**: 読みやすさの改善、または影響範囲が大きく先に設計が必要なもの。どれも単独の PR にでき、API・CLI・JSON・DB スキーマを変えない (F8 だけは、これまで不正な JSON になっていた入力で Slack 通知の内容が変わる)。

### F1. MIME と encoding の文字列を定数に集約する (中)

- 対象: `processing/pipeline/FileProcessingPipeline.kt:467` (`"video/vnd.dlna.mpeg-tts"`, `"gzip"`)、`processing/api/MimeTypeComponent.kt:26-28` (`"video/mp4"`, `"video/vnd.dlna.mpeg-tts"`)、`processing/api/ValidateCompletedComponent.kt:41` (`"gzip"`)、`manager/api/controller/VideoFileController.kt:42` (`"video/mp4"`)、`catalog/domain/CreatedFile.kt:20,25`
- 現状の問題: TS の MIME は `CreatedFile.TS_MIME` として定数があるのに、アップロード登録 (`saveArchive`) と拡張子表 (`MimeTypeComponent`) は同じ文字列を直書きしている。mp4 の MIME と `gzip` は定数が無い。`MimeTypeComponent` の KDoc も「`CreatedFile.isMp4` / `isTs` と一致している必要がある」と、人の注意に頼っている。打ち間違えると、`hasTsFile`、検索 (`ProgramSearchSql` の fileType)、`ValidateCompletedComponent`、再生可否 (`findStreamableVideo`) の判定が黙って false になる。
- 改善案: `CreatedFile` の companion object に `MP4_MIME` を足し (`TS_MIME` と同じ形)、gzip の encoding 名も定数にする (置き場所は catalog の `CreatedFile` か processing の `MimeTypeComponent` のどちらか。processing → catalog の依存は許可されている)。上記の直書きを定数参照に置き換える。companion object の `const val` は JSON に出ない。
- 利益: MIME / encoding の値の定義が 1 か所になり、打ち間違いによる判定の不一致をコンパイル時の参照で防げる。KDoc の「一致させる」という注意書きが不要になる。
- 挙動変更リスク: 低。値が同じ定数への置き換えなので、保存される値は変わらない。
- 受け入れ条件: 上記ファイルから MIME / encoding の文字列リテラルが無くなる (SQL の `ProgramSearchSql` は既に定数を補間している)。保存値・API 応答が変わらない。
- 検証: `MimeTypeComponentTest`、`FileProcessingPipelineTest` (登録時の mime / encoding の引数)、`ValidateCompletedComponentTest`、`VideoFileControllerTest`、`./gradlew build`。

### F2. 台帳の欠損値を表すセンチネル値を整理する (低)

- 対象: `catalog/persistence/ProgramDto.kt:33-68` (`drops ?: -1`、`size ?: 0`、`recordedAt ?: LocalDateTime.MIN`、`channel ?: ""`、`duration ?: -1.0`)、`catalog/api/ProgramCommand.kt:78-91` (dry-run の `insert`)、`processing/api/tool/DurationProbeClient.kt:41-51` (失敗時 `0.0`)。利用側: `library/api/DuplicateProgramCommand.kt:23,62,89,95-96`、`processing/domain/RecordingQuality.kt:88,99` (`takeIf { it >= 0 }` / `> 0`)、`library/api/BulkLibraryCommand.kt:356-372` (fingerprint)、`library/api/CleanupPlanCommand.kt:148-153`
- 現状の問題: 「録画行が無い」「長さを測れなかった」が型の null ではなく特別な値で表され、利用側がそれぞれ `LocalDateTime.MIN` との比較や `<= 0` で判定している。`ProgramInventory.allPrograms` の KDoc が「recording fields are defaults」と書いて補っている。
- 改善案: すぐに型を変えるのではなく、まず欠損の判定を名前の付いた 1 か所にまとめて、利用側をそれに置き換える (例: catalog の拡張関数 `Program.hasRecording()` / `durationKnown()`)。`Program` のプロパティとして足すと JSON に出てしまうため、関数にする。`Program` の各フィールドを nullable にするのはその後の設計判断とする。
- 利益: 欠損の判定方法が利用側ごとにばらつかなくなり、センチネル値の変更や nullable 化の影響範囲が 1 か所に集まる。
- 挙動変更リスク: 中〜高。`Program` / `ProgramDetail` は manager:api がそのまま JSON にするため、フィールドの型やプロパティを変えると API 応答が変わる。`BulkLibraryCommand.fingerprint` はこれらの値をハッシュに含めるので、表現が変わると既存のプレビューがすべて STALE になる。`executed_file.duration` に保存済みの `0.0` もある。
- 受け入れ条件 (第 1 段階): API 応答・CLI 出力・fingerprint・DB 保存値が変わらない。欠損の判定がまとまり、`LocalDateTime.MIN` との直接比較が catalog の外から無くなる。
- 検証: `DuplicateProgramCommandTest`、`BulkLibraryCommandTest` (fingerprint の値)、`CleanupPlanApplyTest`、`ProgramControllerTest` (JSON)、`RecordingQualityTest`、`./gradlew build`。

### F3. ProgramDto の既定値の重複をなくす (低)

- 対象: `catalog/persistence/ProgramDto.kt:33-68` の `toDomain` と `toProgramDetail`
- 現状の問題: 欠けた録画列の既定値 (F2 のセンチネル値) を 2 つの関数がそれぞれ列挙しており、片方だけ直すと `Program` と `ProgramDetail` で値が食い違う。
- 改善案: `toProgramDetail` を `toDomain()` の結果から組み立てる (`val program = toDomain()` から各フィールドを写す)。F2 より先に単独で実施できる。
- 利益: `Program` と `ProgramDetail` の既定値が食い違う余地が無くなり、F2 で既定値を見直すときの修正箇所も 1 つになる。
- 挙動変更リスク: 低。生成される値は同じ。
- 受け入れ条件: 既定値の定義が 1 か所になる。`ProgramCommandTest` の `findDetail` と mapper テストが期待値を変えずに通る。
- 検証: `./gradlew.bat catalog:test`、`ktlintCheck`。

### F4. checked な Exception の送出を個別に見直す (低)

- 対象: `catalog/api/ProgramCommand.kt:74` (`insert` 後の再取得失敗)、`:131` (`delete` の ExecutedFile 欠損)、`storage/samba/SambaNasComponent.kt:58` (`getResource`)、`:86` (`deleteResource` の対象なし)
- 現状の問題: Kotlin の `Exception` は Java の checked 例外で、Spring の `@Transactional` はこれでは rollback しない。`CreatedFileCommand` はこの点をコメント付きで `RuntimeException` に包み直して回避している (`CreatedFileCommand.kt:54,95`) が、ほかの送出箇所は同じ前提を暗黙に持つ。現状を確認した範囲では、誤ってコミットされる経路は見つからなかった。`ProgramCommand.delete` は書き込みの前に送出し、`insert` はトランザクションを持たない `FileProcessingPipeline.registerRecording` から呼ばれる。`deleteResource` の例外は `CreatedFileCommand.delete` が包み直す。ただし、呼び出し元が変わると rollback の条件が黙って変わる。
- 改善案: 例外型の一括置換はしない (Issue の範囲外)。上の 4 か所だけを、呼び出し元ごとに確認したうえで `error(...)` (`IllegalStateException`) など意味の分かる非 checked 例外にする。`getResource` は本番の呼び出し元が無い (module-boundaries U4) ので、置き換えずに削除する案もある。
- 利益: 例外の意味と rollback 条件が送出箇所から読み取れるようになり、呼び出し元が変わったときに rollback されない経路が黙って生まれるのを防げる。
- 挙動変更リスク: 低〜中。rollback の条件が「コミット」から「rollback」に変わりうる箇所があるため、呼び出し元のトランザクション境界を 1 つずつ確認する。`shouldThrow<Exception>` のテストは、サブクラスでも通るので変更不要。
- 受け入れ条件: 対象の 4 か所が checked の `Exception` を送出しない。既存テストが期待値を変えずに通る。`CreatedFileCommand` の包み直しのコメントと矛盾しない。
- 検証: `ProgramCommandTest`、`NasComponentTest` (storage)、`CreatedFileCommandTest`、`ResetRunnerTest`、`BulkLibraryCommandTest`、DB を使う統合テストを含む `./gradlew build`。

### F5. 名前の shadowing と不要な nullable を解消する (低)

- 対象:
  - `library/api/LibraryOrganizationCommand.kt:53-55,114-118`: ローカル変数 `key` (生成キーの受け皿) と private 関数 `key(name)` が同名
  - `library/api/LibraryVerificationCommand.kt:34-43`: ローカル `programs` がプロパティ `programs` を隠し、`this.programs` と書き分けている
  - `console-common/.../TerminalTextColorComponent.kt:28-36`: `decorateString(colorCode: Int?)` の nullable と null 分岐。呼び出し元はすべて非 null を渡す
- 現状の問題: どれもコンパイル・実行上の問題は無いが、読むときにどちらの `key` / `programs` かを確かめる必要がある。null が来ない引数を nullable にすると、null が来る経路があるように読める。
- 改善案: ローカル変数を `keyHolder` / `targets` などに改名する。`decorateString` の引数を `Int` にして null 分岐を消す (private 関数なので公開 API は変わらない)。
- 利益: どの変数・プロパティを参照しているかが名前だけで分かり、`this.` による書き分けや、来ない null を想定した読み方が不要になる。
- 挙動変更リスク: なし (改名と到達しない分岐の削除のみ)。
- 受け入れ条件: shadowing と不要な nullable が無くなり、テストが期待値を変えずに通る。
- 検証: `LibraryOrganizationCommandTest`、`LibraryVerificationCommandTest`、`TerminalTextColorComponentTest`、`ktlintCheck`。

### F6. AfterEncodeRunner.run の dry-run と実行の分岐を整理する (低)

- 対象: `processing/api/AfterEncodeRunner.kt:69-211` (`run`)
- 現状の問題: 約 140 行の関数で、dry-run かどうかが `job` / `token` / `attempt` が null かどうかで表され、`checkNotNull(job)` や `job?.id` が分散している (`:98,115,135,144`)。claim の解放 (`finally`)、完了後の失敗の扱い (`completed`)、中断の伝播が同じ関数に重なっている。
- 改善案: #106 と同じ方針で、挙動を変えない抽出をする。例: claim の取得までを `claimCallback(...)` にまとめ、dry-run の経路 (ファイルの存在確認と記録だけ) を別の private 関数に分ける。実行モードを型 (sealed class など) で表すかは、抽出の後で判断する。
- 利益: dry-run と実行の経路を別々に追えるようになり、claim の解放や完了判定を変更するときに影響範囲を確かめやすくなる。
- 挙動変更リスク: 中。callback claim の解放順、`completed` の判定、Slack 通知の回数と順序を保つ必要がある。
- 受け入れ条件: 純粋なリファクタリングとし、`AfterEncodeRunnerTest` を期待値を変えずに通す。
- 検証: `AfterEncodeRunnerTest`、`AfterEncodeCommandTest`、`./gradlew build`。#106 と同じ PR にはしない。

### F7. ProgramController.get の重複した読み取りをまとめる (低)

- 対象: `manager/api/controller/ProgramController.kt:65-76`
- 現状の問題: `find(id)`、`findDetail(id)`、`videoFiles(program)` の 3 回の呼び出しで、program 行を 2 回、created_file を 2 回読む。`videoFiles` の結果は `findDetail` の `createdFiles` と同じ問い合わせ (`selectByExecutedFileId`) の結果で、同じスナップショットからではないので、同時に変更されると 2 つが食い違いうる。
- 改善案: `findDetail(id)` だけを呼び、`videoFiles = detail.createdFiles` とする。応答の形 (`ProgramDetailResponse(program, videoFiles)`) は変えない。
- 利益: DB の読み取りが減り、`program` と `videoFiles` が同じスナップショットから作られるため食い違わなくなる。
- 挙動変更リスク: 低。応答の JSON は同じ。mapper の呼び出し回数が減るので、`ProgramControllerTest` の mock の検証は書き換えが必要。
- 受け入れ条件: `GET /api/v1/programs/{id}` の応答 (200 / 404 とも) が変わらない。
- 検証: `ProgramControllerTest` (応答の JSON 比較)、`./gradlew manager:api:test`、frontend からの表示確認。

### F8. SlackClient の JSON 生成を Jackson にする (低)

- 対象: `processing/external/slack/SlackClient.kt:51-61` (`toJson`)
- 現状の問題: `\`、`"`、`\n`、`\r`、`\t` だけを手でエスケープしており、そのほかの制御文字 (U+0000〜U+001F) を含むメッセージは不正な JSON になる。例外メッセージやパスがそのまま入るため、ありうる入力。失敗は警告ログだけになり、通知が届かない。コード内のコメント (`If the complexity increases, please replace it with Jackson`) も置き換えを想定している。
- 改善案: processing が既に使っている `tools.jackson` の `JsonMapper` (`RecordingQualityCommand.kt:24`) で `mapOf("text" to message)` をシリアライズする。
- 利益: 制御文字を含む失敗メッセージでも Slack 通知が届き、手書きのエスケープを保守する必要が無くなる。
- 挙動変更リスク: 低。これまで不正だった入力が正しく送られるようになる。正常な入力で生成される JSON は同じ。
- 受け入れ条件: 制御文字を含むメッセージでも正しい JSON を送る。
- 検証: `SlackClientTest` に制御文字のケースを追加。`./gradlew processing:test`。

### F9. 両 CLI の引数解析ヘルパーを console-common に寄せる (低)

- 対象: `manager/console/.../ConsoleRunner.kt:26-33` (`parseManager`)、`processor/console/.../ConsoleRunner.kt:55-62` (`parseProcessor`)
- 現状の問題: どちらも「`UsageError` を終了コード 2 の `CliktError` にする」同じ拡張関数で、片方だけ直すと両 CLI の終了コードの規約がずれる。
- 改善案: console-common に 1 つの拡張関数 (例: `parseWithUsageExit2`) を置き、両 CLI から使う。console-common は「両 console が実際に使うもの」だけを置く方針で、これに当てはまる。
- 利益: 不正な引数での終了コードの規約が 1 か所で定義され、両 CLI でずれなくなる。
- 挙動変更リスク: 低。終了コードとヘルプの出力は同じ。
- 受け入れ条件: 両 CLI の不正な引数での終了コードが 2 のまま。console-common の境界 (業務モジュールへの依存なし) を守る。
- 検証: `TsVideosManagerTest`、`TsVideosProcessorTest` (終了コード 2 のケース)、console-common のテスト追加、`./gradlew build`。

## 6. 変更不要とした主な項目

| ファイル・シンボル | 一見した問題 | 維持する理由 |
| --- | --- | --- |
| `catalog/persistence/GeneratedKeyHolder.kt:5` ほか library / processing の同名クラス | `var id` | MyBatis が生成キーを書き込むための受け皿。各モジュールが自分の copy を持つのは境界の方針 (module-boundaries §4.2) |
| `ProgramCommand.moveCreatedFiles` の `moved` (`ProgramCommand.kt:160`) | 可変リスト | 移動済み NAS ファイルの復旧記録。失敗時に逆順で戻す (`CreatedFileCommand.restoreMoved`) 順序が契約 |
| `FileProcessingPipeline.processFile` の `var registered` / `splitInvoked` / `createdArchive` | ラムダから書き換える `var` | ロールバック関数が「どこまで進んだか」を知るための共有状態。消すと rollback の対象を正しく決められない |
| `VerifiedUploadCommand.withClaim` の `catch (e: Throwable)` と `runCatching` (`VerifiedUploadCommand.kt:468-476`) | `Throwable` を捕捉 | `Error` でも claim を残さず、解放の失敗を suppressed に付けて元の例外を優先するための意図的な選択 |
| `SambaNasComponent.discardCopy` の `runCatching` (`SambaNasComponent.kt:185,190`) | 失敗の握りつぶし | 失敗した move の後片付けで、元の例外を上書きしない。結果はログに残る |
| `EnvironmentDiagnosticCommand.setting` / `validUri` の `runCatching` | 失敗の握りつぶし | 設定が壊れていても doctor が診断を続けるため。ただし `setting` が握りつぶした例外は `diagnosticCancellation` に渡らないので、割り込みフラグを伴わない `InterruptedException` は伝播しない (フラグが立っていれば後続の `diagnosticCancellation()` で検出される)。通常の property source の解決では起こりにくいため、許容する境界とした ([§2.3](#23-diagnostics)) |
| `use` の深い入れ子 (`SambaVerifiedUploadNas.kt`、`DatabaseDiagnosticProbe.kt`) | スコープ関数の入れ子 | close 順 (内側から) を構文で保証している。平らにすると close の範囲が読めなくなる |
| `StorageOverviewCommand.overview` の `var bytes` / `excluded` | `var` とループ | 1 回の走査で 2 つの値を数える単純なループ。`sumOf` と `count` に分けると 2 回走査することになり、読みやすくもならない |
| `ExecutedFileTag` / `ExecutedFileCheck` の文字列定数 | 文字列の状態値 | 検出器を DB スキーマの変更なしで追加するための設計。値は DB に保存される |
| `CleanupPlan.action` / `CleanupFile.status` の `String` | 文字列の状態値 | オペレーターが保存・確認する JSON 計画ファイルの形式。`CleanupPlanCommand.validate` が値を検証する |
| `ShellClient.run` の `var stdout` / `stderr` / `exitedNormally` | `var` | `finally` で子プロセスを片付ける条件に必要 |
| `AribCaptionData` / `EmergencyBroadcastDetector` / `TsUtil` の `var p` などの位置カウンタ | `var` とループ | バイト列パーサ。インデックスのループが最も読みやすく、速い |
| `Search.read` の行ごとの `hasTsFile` (`Search.kt:84-104`) | N+1 クエリ | コード内の TODO に記録済み。検索 SQL の変更にあたり、Kotlin のコード改善の範囲外 |
| `ProcessingCancellation.isInterrupted` / `diagnosticCancellation` / `BulkLibraryCommand` の cause 連鎖の判定 | 似た処理の重複 | モジュール間に依存が無く、共通化のためだけに共有モジュールを作らない方針 |
| `SambaConfigurationProperties` (data class) | `toString()` に `password` が出る | 現時点でログなどに出す経路は無い。この型をログに出す変更をするときに見直す |

## 7. 検証

本 Issue の変更 ([§4](#4-本-issue-で実施した変更)) の検証は、`backend/` で次を実行する (Windows は `./gradlew.bat`)。

```powershell
./gradlew.bat catalog:test --tests "me.pinfort.tsvideos.catalog.api.ProgramCommandTest"
./gradlew.bat ktlintCheck
./gradlew.bat build
```

`build` はテストと各モジュールの check (境界検査を含む) を含み、DB を使うテストのために Docker (Testcontainers) が必要。実行したコマンドと結果は PR に記録する。
