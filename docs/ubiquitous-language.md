# ユビキタス言語

tsvideos で使う用語の一覧。コード・DB・UI・ドキュメントで同じ言葉を同じ意味で使うための辞書。

backend と frontend の両方、さらに `processor` と `manager` をまたいで使われる用語を扱うため、リポジトリ直下の `docs/` に置く。

## 録画・番組

| 用語 | 英語 / コード上の名前 | 意味 |
| --- | --- | --- |
| TS抜き | - | 放送波から TS (MPEG-2 TS) をそのまま録画すること。またはその録画ファイル (`.m2ts`) |
| 録画 / 録画ファイル | `ExecutedFile` (`executed_file`) | processor が取り込んだ TS 抜き録画の元ファイル。処理の起点で、処理後も元ファイルは残す |
| 番組 | `Program` (`program`) | 録画から作られ、manager が管理する単位。エンコード後の成果物 (作成ファイル) を束ねる |
| 番組詳細 | `ProgramDetail` | 番組に作成ファイル・タグ・チェックを加えた表示用のモデル |
| 分割ファイル | `SplittedFile` (`splitted_file`) | TsSplitter が録画から分割したファイル。本編にあたるものをメインの分割ファイルと呼ぶ |
| 作成ファイル | `CreatedFile` (`created_file`) | Amatsukaze のエンコードで作られた成果物 (主に mp4)。NAS に保管される。動画の解像度 (`width`/`height`) を持つ。動画以外のファイルと解像度記録前のデータは未設定 (null) |
| チャプタ | `VideoChapter` (`created_file_chapter`) | mp4 の作成ファイルに埋め込まれた区間 (開始・終了の秒と title)。Amatsukaze (join_logo_scp) が付け、CM 区間も含む。title (`A`, `CM`, `CM?` など) は機械的なラベルで一意ではなく、無いこともある。ffprobe で取得して DB に保存し、プレーヤーのチャプタ一覧に使う |
| チャプタ取得済み | `VideoChapters.probed` (`created_file.chapters_probed`) | チャプタを読み取ったかどうか。取得済みで 0 件 (チャプタの無いファイル) と未取得 (導入前の登録・ffprobe 失敗) を区別する。未取得の mp4 は `tvpcli chapters backfill` で取得する |
| チャンネル | `channel` | 放送のチャンネル番号 |
| チャンネル名 | `channelName` | 放送局名 |
| ドロップ | `drops` | 録画中に欠落したフレーム数。TsSelect の `tsDump` で数える |
| 録画日時 | `recordedAt` | 録画開始日時。ファイル名 `[yyMMdd-HHmm][チャンネル][チャンネル名]タイトル` から得る |
| 再生時間 | `duration` | 録画・ファイルの長さ (秒) |

## 処理 (processor)

| 用語 | 英語 / コード上の名前 | 意味 |
| --- | --- | --- |
| パイプライン | `FileProcessingPipeline` | ドロップチェック → TsSplitter → 圧縮 + NAS アップロード → Amatsukaze タスク追加 の 4 段階。失敗時は当該段階と以前の段階を逆順にロールバックする |
| ドロップチェック | - | パイプライン最初の段階。ドロップ数を数え、緊急警報放送・文字スーパーも検出する |
| 分割 | split | TsSplitter で録画を番組本編などに分けること |
| エンコード | encode | Amatsukaze で TS を mp4 などに変換すること |
| エンコード後処理 | `AfterEncodeRunner` (`after-encode`) | Amatsukaze のエンコード完了後に、作成ファイルの登録・NAS への保管・ローカルファイルの削除・番組の完了を行う。ロールバックしない |
| リセット | `ResetRunner` (`reset`) | 1 つの録画について番組と生成物をすべて削除し、処理前の状態に戻すこと。元の録画は残す |
| ドライラン | `--dry-run` | 書き込みを行わず動作だけ確認する実行方法 |
| 処理ジョブ | `ProcessingJob` | 録画 1 件の処理状況を記録する単位。同じ録画を重複して処理しないための冪等性管理に使う |
| 処理識別子 | `ProcessingIdentity` | パス・サイズ・更新時刻から作る録画の版の指紋。内容のハッシュではない |
| 検証付きアップロード | `VerifiedUploadCommand` | NAS への保管を、所有権の記録 → 一時ファイルへの排他作成と転送 → 検証 → 上書きしないリネーム → 登録 の順で行うこと。登録と SHA-256 の再確認が済むまでローカルファイルは消さない |
| アップロード台帳 | `UploadManifest` (`upload_manifest`) | 処理ジョブの成果物 1 つにつき 1 行。保管先・一時ファイル名・元ファイルの指紋と SHA-256・状態・クレームを持ち、どの NAS ファイルをこの処理が所有するかの証拠になる |
| 受領証 | `VerifiedUploadReceipt` | 検証付きアップロードが返す台帳 ID。登録・ローカル削除のたびに台帳を読み直して検証する |
| 処理履歴 | `ProcessingHistoryCommand` | 処理ジョブごとの実行の記録。診断用の証拠であり、ファイル削除や外部操作の再実行の根拠にはしない。`tvpcli status` / `tvpcli history` で見る |
| 試行 | `ProcessingAttempt` (`processing_attempt`) | `process` (PROCESS) または `after-encode` (CALLBACK) の 1 回の実行。ジョブごとに単調増加する番号と ACTIVE / COMPLETED / FAILED / INTERRUPTED の結果を持つ |
| 段階イベント | `ProcessingStageEvent` (`processing_stage_event`) | 試行内の 1 段階 (INSPECT / SPLIT / COMPRESS / UPLOAD_ORIGINAL / SUBMIT_ENCODER / UPLOAD_ENCODED / FINALIZE) の開始・終了・結果・固定のエラーコード・生成物の参照 |
| 履歴識別子 | `ProcessingHistoryIdentity` (`processing_history_identity`) | 試行番号の採番と、処理ジョブがリセットで消えた後も残す録画の指紋・パス。キューやクレームではない |

## ステータス

| 対象 | 値 | 意味 |
| --- | --- | --- |
| 録画 (`ExecutedFile.Status`) | `REGISTERED` | 登録済み |
| | `DROPCHECKED` | ドロップチェック済み |
| | `SPLITTED` | 分割済み |
| 分割ファイル (`SplittedFile.Status`) | `REGISTERED` | 登録済み |
| | `COMPRESS_SAVED` | 圧縮して NAS に保存済み |
| | `ENCODE_TASK_ADDED` | Amatsukaze にエンコードタスクを追加済み |
| 作成ファイル (`CreatedFile.Status`) | `REGISTERED` | 登録済み |
| | `ENCODE_SUCCESS` | エンコード成功 |
| | `FILE_MOVED` | 保管先 (NAS) へ移動済み |
| 番組 (`Program.Status`) | `REGISTERED` | 登録済み (処理中) |
| | `COMPLETED` | すべての処理が完了 |
| | `ERROR` | 処理に失敗 |

## 検出・タグ

| 用語 | 英語 / コード上の名前 | 意味 |
| --- | --- | --- |
| タグ | `ExecutedFileTag` (`executed_file_tag`) | 検出処理が録画に付ける印。検出結果はカラムではなくタグで持つ |
| チェック | `ExecutedFileCheck` (`executed_file_check`) | どの検出処理を実行したかの記録。タグが無い場合に「検出なし」と「未検査」を区別する |
| 検出なし | `detected(tag) == false` | 検出処理を実行済みで、タグが付かなかった状態 |
| 未検査 | `detected(tag) == null` | 検出処理を実行していない状態 (導入前に登録した録画など) |
| 緊急警報放送 | `ews` | EWS。緊急警報放送を検出したときに付くタグ |
| 文字スーパー | `superimpose` | ARIB 字幕データの文字スーパーを検出したときに付くタグ |
| コレクション | `LibraryKind.COLLECTION` (`library_collection`) | 運用者が手動で番組をまとめる入れ物。番組 (`program`) に関連付ける |
| 手動タグ | `LibraryKind.TAG` (`library_tag`) | 運用者が手動で番組に付ける名前。検出処理のタグ (`executed_file_tag`) とは別物で、名前・ID を共有しない。API では `libraryTagId` / `library-tags` |

## 管理 (manager)

| 用語 | 英語 / コード上の名前 | 意味 |
| --- | --- | --- |
| 重複番組 | `DuplicateGroup` | 同じ放送を録画したと判定された番組のまとまり。先頭が残す候補 |
| 同一放送 | `SAME_BROADCAST` | 同一チャンネル・同一開始時刻 |
| 時間帯重複 | `OVERLAP` | 同一チャンネルで録画時間帯が重なっている |
| 重複レビュー | `DuplicateReview` (`/api/v1/programs/duplicates`, `/duplicates`) | 重複番組を並べて比較し、残す候補の理由を示す読み取り専用ビュー。削除は選んだ番組だけを一括操作の流れで行い、自動では整理しない |
| 意図した重複 / 非表示 | `hidden` (`library_duplicate_dismissal`、`/api/v1/programs/duplicates/hide`, `/restore`) | 運用者が確認して残すと決めた重複番組のまとまり。検出理由と番組の集合で識別し、重複レビューの既定の一覧から外す。再表示でき、メンバーや理由が変わると再びレビューに出る。検出・整理プラン・ストレージ概要には影響しない |
| 推薦の決め手 | `decisiveCriterion` (`DuplicateKeepCriterion`) | 残す候補が 2 番目の番組より先に並んだ最初の基準。基準の順序はドロップ数 → 状態 → 再生時間 → 録画サイズ → id |
| ストレージ概要 | `StorageOverview` (`/api/v1/storage`, `/storage`) | 登録メタデータから見た容量・大きい録画・重複の整理候補の読み取り専用ビュー。ファイルは検査も削除もしない |
| 登録メタデータ容量 | `registeredBytes` | `created_file.size` に登録された値の合計。NAS 上の実在や現在のサイズは保証しない。未検証の物理使用量は `null` (不明) で、0 とは区別する |
| 保持を推薦 / 整理候補 | `retained` / `removable` | 重複番組のうち残す 1 件と、それ以外の番組。重複番組のまとまり同士は番組を共有しない |
| 回収候補容量 | `estimatedReclaimableBytes` | 整理候補をすべて除去したと仮定した推計。参照する全番組が整理候補のパスだけを 1 度数える |
| 共有パス | `excludedSharedPathCount` | 整理候補と保持する番組の両方が参照するため、回収候補から除外したパス |
| 整合性検証 | `LibraryVerificationCommand` (`tvmcli verify`) | 登録済みの作成ファイルが NAS 上に存在し、記録サイズと一致するかを読み取り専用で確かめること。内容のデコードまでは保証しない |
| 検証結果 | `VerificationStatus` | `VERIFIED` (存在しサイズ一致) / `MISSING` (両 NAS で確認できたうえで存在しない) / `SIZE_MISMATCH` (サイズ不一致) / `INACCESSIBLE` (接続・権限などで確認できない。欠損とはみなさない) / `UNCHECKED` (転送未完了・未登録・無効なパスなどで検査しなかった) |
| 検証の実行 | `HealthRun` (`health_verification_run`) | `/health` から明示的に始める整合性検証 1 回。RUNNING / COMPLETED / FAILED の状態を持ち、RUNNING は同時に 1 つだけ。失敗しても前回完了した結果は残る |
| 保存済みの検証結果 | `HealthFinding` (`health_verification_finding`) | 完了した検証の実行が記録したファイルごとの検証結果。過去の時点のもので、その後の NAS の変化は反映しない。番組が削除されても残る |
| 整理プラン | `CleanupPlan` (`tvmcli cleanup plan` / `apply`) | 重複番組または録画日時の締め切りから削除対象と残す番組を書き出した JSON。人が確認してから `--yes` で適用する。適用時は対象ごとに現在の状態でプラン全体を再確認し、変わっていれば STALE として削除しない |
| 一括操作 | `BulkLibraryCommand` (`/api/v1/programs/bulk/*`) | 表示中ページで選んだ完了済み番組 (最大 100 件) の作成ファイルを、まとめて削除 (`DELETE`) または別の番組フォルダーへ移動 (`MOVE`) すること。元の録画とローカルの分割ファイルは残す。プレビューしてから実行する |
| 一括操作の指紋 | `fingerprint` | プレビュー時の番組・録画・分割ファイル・作成ファイル・移動先・共有状況から作る SHA-256。実行時に再計算して一致しなければ `STALE` とする。同時実行の検出用で、認証には使わない |
| パスの別名 | `key()` | 大文字小文字・区切り文字 (`/` と `\`)・末尾のドットや空白だけが違う NAS パス。SMB では同じファイルを指すため、一括操作では同じパスとして扱う |

## 外部ツール・環境

| 用語 | 意味 |
| --- | --- |
| TsSplitter | 録画 TS を分割するツール (`TsSplitterClient`) |
| TsSelect | ドロップ数のカウントなどを JVM 内で行うライブラリ (`TsSelectClient`) |
| Amatsukaze | TS をエンコードするソフトウェア。`AmatsukazeAddTaskClient` でタスクを追加する |
| NAS | 圧縮ファイルや作成ファイルの保管先。Samba 経由でアクセスする |
| `tvpcli` | processor のコマンドラインツール (`process` / `after-encode` / `reset` / `release-claim` / `status` / `history`) |
