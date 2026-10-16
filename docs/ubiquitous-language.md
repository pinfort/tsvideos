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
| 作成ファイル | `CreatedFile` (`created_file`) | Amatsukaze のエンコードで作られた成果物 (主に mp4)。NAS に保管される |
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

## 管理 (manager)

| 用語 | 英語 / コード上の名前 | 意味 |
| --- | --- | --- |
| 重複番組 | `DuplicateGroup` | 同じ放送を録画したと判定された番組のまとまり。先頭が残す候補 |
| 同一放送 | `SAME_BROADCAST` | 同一チャンネル・同一開始時刻 |
| 時間帯重複 | `OVERLAP` | 同一チャンネルで録画時間帯が重なっている |

## 外部ツール・環境

| 用語 | 意味 |
| --- | --- |
| TsSplitter | 録画 TS を分割するツール (`TsSplitterClient`) |
| TsSelect | ドロップ数のカウントなどを JVM 内で行うライブラリ (`TsSelectClient`) |
| Amatsukaze | TS をエンコードするソフトウェア。`AmatsukazeAddTaskClient` でタスクを追加する |
| NAS | 圧縮ファイルや作成ファイルの保管先。Samba 経由でアクセスする |
| `tvpcli` | processor のコマンドラインツール (`process` / `after-encode` / `reset` / `release-claim` / `status` / `history`) |
