# 処理の同一性と整合 (#64)

既存の環境をアップグレードする前に、processor のコマンドを停止し、アプリケーションデータベースへ
`database-schema/src/main/resources/db/migrations/064_processing_job.sql` を適用する。
このスクリプトは新しいテーブルを作成するだけで、既存の登録は変更しない。
新規のデプロイでも、元のテーブルを作成した後にこれを適用する必要がある。Testcontainers は
初期化スクリプトで同じテーブル定義を使う。

ジョブキーは、正規化された絶対入力パス、NUL 区切り、バイト長、もう 1 つの NUL 区切り、
最終更新時刻のミリ秒を連結したものの SHA-256 である。これはバージョンのフィンガープリントであり、
内容のハッシュではない。サイズとタイムスタンプが同一であれば、内容が変わっても検出できない。
ファイルを復元する際は、同じファイルシステムのパス表記を使い、メタデータを保持すること。
録画は正規パスで識別される。064 より前に登録された行は、渡されたとおりのパス (絶対パスだが、
シンボリックリンク経由の場合がある) を保存している。`reset` とコールバックはその表記にフォールバックするが、
`process` は別のパスと見なし、program 名のチェックに頼ることになる。
フィンガープリントが変わった場合は新しいジョブとなるが、既存のパス/名前での登録には
明示的な reset が必要で、暗黙に再利用されたり上書きされたりすることはない。検査/ドロップチェックの
失敗ではジョブは作られない。アップロード前にロールバックが証明された場合は、RETRYABLE のクレームが許可される。
ロールバックのいずれかの手順が失敗した場合、または RETRYABLE の記録自体が失敗した場合、ジョブは CLAIMED のままになる。
圧縮まで (登録、ジョブの紐づけ、分割、gzip) の失敗は、部分的なローカルのアーカイブも含めてロールバックされ、
RETRYABLE になる。元ファイル用の NAS へのアップロードが始まった後は、ロールバックが成功しても
ジョブは CLAIMED のままになる。NAS 上に部分的または完全なコピーが存在する可能性があるためである。
対応するジョブがなく登録済みの録画 (アップグレード前の登録、引き継いだ従来のジョブ、フィンガープリントの変更)、
または整合待ちのジョブを持つ録画は、`process` を実行するたびに失敗するため、ディレクトリの再スキャンのたびに
Slack へ通知される。これは意図的なもので、オペレーターが録画を reset または整合させるまで通知が繰り返される。
クラッシュや不確かな外部の副作用は、自動では決して取り戻されない。

ジョブの状態: CLAIMED (実行中または中断)、RETRYABLE (安全に再試行できると判明)、
SUBMISSION_UNKNOWN (エンコーダ呼び出しの前に意図をコミット済み)、SUBMITTED (呼び出しが
0 を返した)、COMPLETED (コールバック完了)、REJECTED (エンコーダのタスクがないとオペレーターが確認済み)、
RESETTING (クリーンアップを予約済み)。0 の戻り値は受理の通知であり、exactly-once の証明ではない。
コールバックはジョブをアトミックにクレームし、エンコーダのアイテム ID とソート済みの正規出力パスの
ハッシュを永続化する。マニフェストが変更されている場合は拒否される。既存の created_file の行が
成果物の台帳となる: ENCODE_SUCCESS は保留中、FILE_MOVED は永続的にアップロード済みを表す。
保留中のアップロードは、削除の前に、正確な動画用 NAS のターゲットの長さを検証する。再試行では、
DB の更新前に完了していたアップロードが見つかることがある。台帳の行がないターゲットは
拒否される。完了済みの成果物は、ローカルファイルが欠落していても許容される。コールバックが
未完了の間は、NAS の宛先の設定を変更しないこと。長さはチェックサムではない。より強い公開時の
検証はフォローアップの #72 で扱う。

## オペレーターの手順 (暗黙の再投入なし)

クレームを編集する前に、対象の録画に関わるすべての `process`、`after-encode`、`reset`、
エンコーダの後処理の起動を停止する。エンコーダのキュー/履歴と、ローカル/NAS の成果物を確認する。
履歴が参照できない場合に、経過時間、ネットワーク障害、空のキューから拒否を推測してはならない。
先に、対象の DB の行をバックアップする。
以下の `SELECT * FROM processing_job ORDER BY id` で見つけた数値の ID を使う。
すべての UPDATE は compare-and-set であり、ちょうど 1 行が更新されることを確認し、状態を読み直すこと。

SUBMISSION_UNKNOWN の場合は、入力パスからエンコーダのタスクを確実に特定する:

```sql
SET @job_id = 123; -- replace with inspected ID
-- Only after confirming the encoder accepted the task:
UPDATE processing_job SET state='SUBMITTED', updated_at=UTC_TIMESTAMP(6)
WHERE id=@job_id AND state='SUBMISSION_UNKNOWN' AND callback_claim IS NULL;
-- Alternative: only after confirming it was NOT accepted and cannot still run:
UPDATE processing_job SET state='REJECTED', updated_at=UTC_TIMESTAMP(6)
WHERE id=@job_id AND state='SUBMISSION_UNKNOWN' AND callback_claim IS NULL;
```

受理されたジョブは、元のコールバックを待つ/再実行する。拒否されたジョブは、既存の確認付き
`reset` コマンドを使い、その後 `process` を実行できる。UNKNOWN を直接 RETRYABLE に
変更してはならない。登録とアップロードがすでに存在する可能性がある。オペレーターが
キャンセルした、受理済みの SUBMITTED のジョブについては、エンコーダが終了したことを確認してから、
同じガード付きの遷移で SUBMITTED から REJECTED にし、その後 reset する。

ジョブをクレームできない (従来のジョブ、クレームがすでに保持されている、DB エラー)、または
クレームを解放できないコールバックは、Slack に報告し、program のステータスを変更せずに終了する。
中断されたコールバックについては、コールバックのプロセスが生きていないことを確認し、正確な
`callback_claim` の値を記録してから、そのクレームだけを解放し、元のコールバックを再実行する:

```sql
UPDATE processing_job SET callback_claim=NULL, updated_at=UTC_TIMESTAMP(6)
WHERE id=@job_id AND callback_claim='the-inspected-owner-token';
```

変更されたコールバックを強行するために、callback_manifest をクリアしたり FILE_MOVED の行を削除したりしてはならない。
コールバックは、created_file の行がない動画用 NAS のターゲットへのアップロードを拒否する ("Unowned
NAS target exists")。これは、アップロードは完了したが、行を挿入する前にプロセスが死んだ場合に起こる。
コールバックを停止した状態で、NAS のファイルとローカルの出力を比較する (同じ名前、同じ長さ)。
一致し、他の created_file の行がそのターゲットを指していなければ、NAS のファイルを削除して
元のコールバックを再実行する。そうでなければ両方を保持して調査する。
保留中のアップロードが欠落している場合や NAS の検証に失敗した場合は、ローカルの成果物を保持すること。
エンコーダのタスクが存在し得ない CLAIMED の場合は、すべての登録と生成された成果物を確認する。
executed_file の行がある場合は、ワーカーを停止した状態で、確認した CLAIMED のジョブを REJECTED に遷移させ、その後 reset する。登録とジョブの紐づけの間でクラッシュが起きた場合は、まず、state='CLAIMED' かつ executed_file_id IS NULL を条件とするガード付き UPDATE で、確認したジョブの executed_file_id を、検証済みのその行の ID に設定する。basename から推測してはならない。行がない場合は、出所を確認した後にのみ、所有している部分的な成果物を削除する。
副作用が何も残っていなければ、CLAIMED を RETRYABLE に遷移させる。
実行中の CLAIMED のジョブを reset してはならない。失敗した RESETTING のクリーンアップは、program/executed_file の行が残っている間は再実行できる。それらの行がすでに削除されている場合、reset はジョブをクリアせずに終了する。ワーカーを停止した状態で、残っている所有しているローカルの分割ファイルを確認して削除を完了させ、その後、callback_claim IS NULL の確認済みの RESETTING のジョブ行だけを削除する。そのクリーンアップと
並行してコールバックを実行してはならない。

## 実行中の既存エンコード (従来のジョブの引き継ぎ)

アップグレード前の各エンコードについて、そのコールバックを停止し、executed_file と
splitted_file の行がそのエンコーダのタスクを指していることを確認し、既存の created_file の
行を調べる。既存の FILE_MOVED の行は、完了したアップロードを実際に表していなければならない。
保留中の ENCODE_SUCCESS の行は、出力ファイルを保持している必要がある。無関係なターゲットを引き継いではならない。
これらを確認した後、コールバック専用の従来のジョブを作成する:

```sql
SET @executed_id = 456; -- inspected executed_file ID
INSERT INTO processing_job(identity_key,input_path,input_size,modified_millis,state,executed_file_id)
SELECT SHA2(CONCAT('legacy:',e.id),256),e.file,e.size,-1,'SUBMITTED',e.id
FROM executed_file e WHERE e.id=@executed_id
AND NOT EXISTS (SELECT 1 FROM processing_job j WHERE j.executed_file_id=e.id);
```

挿入がちょうど 1 件であることを確認するか、すでに存在するジョブを確認し、元のコールバックを再実行する。
最初の再実行でマニフェストが固定され、新しいジョブと同様にオーナーシップがクレームされる。従来用のキーは、
失われた元のタイムスタンプを復元したとは主張しない。その後の `process` では、既存の登録の
reset が必要になる。この意図的な引き継ぎにより、アップグレードの際に、無関係な既存の NAS ターゲットが
コールバックに属すると推測することを防ぐ。

## ジョブコマンドの遷移契約 (共通)

`ProcessingJobCommand.claim` が捕捉するのは、重複キーによる挿入の失敗のみ。最初の
INSERT と再試行の CAS は、ProcessingJobClaimCommand を通じて別々の REQUIRES_NEW トランザクションで
実行される。各クレームは作業の開始前にコミットされ、重複インデックスのロックは再試行の更新を試みる前に解放される。
その他の DB エラーは伝播する。
`link` が受け付けるのは、executed-file への紐づけがない CLAIMED の行のみ。`transition` が許可するのは、
期待する状態での CAS による CLAIMED -> RETRYABLE または CLAIMED -> SUBMISSION_UNKNOWN のみ。
`acknowledgeSubmission` は UNKNOWN -> SUBMITTED に進め、すでに COMPLETED のコールバックは維持する。
`complete` が受け付けるのは、UNKNOWN/SUBMITTED/すでに COMPLETED のみ。
再試行、マニフェストの永続化、整合、reset の予約は、アクティビティ時刻を更新する。
