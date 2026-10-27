# 処理の同一性と整合 (#64)

既存の環境をアップグレードする前に、processor のコマンドを停止し、アプリケーションデータベースへ
`database-schema/src/main/resources/db/migrations/064_processing_job.sql` を適用する。
このスクリプトは新しいテーブルを作成するだけで、既存の登録は変更しない。
新規のデプロイでも、元のテーブルを作成した後にこれを適用する必要がある。Testcontainers は
初期化スクリプトで同じテーブル定義を使う。続いて、下の「executed file あたり 1 ジョブ (#108)」の事前確認をしてから
`075_processing_job_executed_file_unique.sql` を適用する。

ジョブキーは、正規化された絶対入力パス、NUL 区切り、バイト長、もう 1 つの NUL 区切り、
最終更新時刻のミリ秒を連結したものの SHA-256 である。これはバージョンのフィンガープリントであり、
内容のハッシュではない。サイズとタイムスタンプが同一であれば、内容が変わっても検出できない。
ファイルを復元する際は、同じファイルシステムのパス表記を使い、メタデータを保持すること。
録画は正規パスで識別される。064 より前に登録された行は、渡されたとおりのパス (絶対パスだが、
シンボリックリンク経由の場合がある) を保存している。`reset` とコールバックはその表記にフォールバックするが、
`process` は別のパスと見なし、program 名のチェックに頼ることになる。
フィンガープリントが変わった場合は新しいジョブとなるが、既存のパス/名前での登録には
明示的な reset が必要で、暗黙に再利用されたり上書きされたりすることはない。ジョブのクレームは検査/ドロップチェックより
前に行われる (クレーム前に行うのは、既存ジョブ・パス・program 名の読み取り専用のガードだけ)。そのため検査/ドロップチェックの
失敗でもジョブは作られ、まだ何も登録していないので RETRYABLE になる (`--dry-run` はクレームしない)。
失敗後のジョブの状態は、どこまで進んだかで決まる:

- 圧縮まで (登録、ジョブの紐づけ、分割、gzip) の段階の失敗では、その段階とそれより前の段階を逆順にロールバックする
  (今回の試行が作成した部分的なローカルのアーカイブも含む)。すべてのロールバックが成功し、executed_file の行が
  残っていないことを確認できた場合だけ RETRYABLE になる。ロールバックのいずれかの手順が失敗した場合、または
  RETRYABLE の記録自体が失敗した場合は CLAIMED のままになる。
- SPLIT/COMPRESS の前後で履歴の書き込み自体が失敗した場合は、ロールバックの段階の外なのでロールバックは走らず、
  登録が残るため CLAIMED になる。
- 元ファイル用の NAS へのアップロードが始まった後の失敗 (アップロード、created_file の登録、分割ファイルの状態更新、
  ローカルのアーカイブ削除) では、ロールバックを一切行わない。NAS 上に部分的または完全なコピーが存在する可能性が
  あるためである。executed_file/program/splitted_file/created_file の行、`upload_manifest`、ローカルの分割ファイルと
  アーカイブ (登録・検証済みで削除されたものを除く) を整合のために残し、ジョブは CLAIMED のままになる。
- エンコーダへの投入の意図をコミットした後 (SUBMISSION_UNKNOWN への遷移後) の失敗でも、同じく何もロールバックせず、
  ジョブは SUBMISSION_UNKNOWN のままになる。
対応するジョブがなく登録済みの録画 (アップグレード前の登録、引き継いだ従来のジョブ、フィンガープリントの変更)、
または整合待ちのジョブを持つ録画は、`process` を実行するたびに失敗するため、ディレクトリの再スキャンのたびに
Slack へ通知される。これは意図的なもので、オペレーターが録画を reset または整合させるまで通知が繰り返される。
クラッシュや不確かな外部の副作用は、自動では決して取り戻されない。

致命的な `Error` (`OutOfMemoryError`、`StackOverflowError` など) は回復しない。パイプラインとロールバックが捕捉するのは
`Exception` だけなので、`Error` はロールバック、履歴の FAILED 記録、RETRYABLE への遷移のいずれも行わずに伝播する。
試行は ACTIVE のまま残り、ジョブは最後にコミットされた遷移の状態のまま残る: エンコーダへの投入の意図をコミットする
前なら CLAIMED、意図のコミットから受理の記録までなら SUBMISSION_UNKNOWN、受理の記録 (`acknowledgeSubmission`) の後
(たとえば最後の履歴の書き込み中) なら SUBMITTED で、その後にコールバックが完了していれば COMPLETED になっている。
JVM が不安定な状態で DB/NAS の後始末を試みるより、永続的な証拠を手動の整合に残すほうが安全なためである。
プロセスが終了したことを確認してから、`tvpcli status`/`history` でジョブの状態と最後の段階を確認し、状態ごとに整合する:
CLAIMED は[ブロックされたジョブの診断](#ブロックされたジョブの診断-runbook)の CLAIMED の項、SUBMISSION_UNKNOWN は
[オペレーターの手順](#オペレーターの手順-暗黙の再投入なし)の SUBMISSION_UNKNOWN の手順に従う。SUBMITTED/COMPLETED の
ジョブは処理自体を終えているので、ジョブの状態は変更しない (CLAIMED の手順を当てはめてはならない)。いずれの場合も、
ACTIVE のまま残った試行は [processing-history.md](processing-history.md) のとおり、オーナーの停止を確認してから明示的に終了させる。

## executed file あたり 1 ジョブ (#108)

`075_processing_job_executed_file_unique.sql` は `processing_job.executed_file_id` の非一意インデックスを一意インデックスに
置き換える。紐づけのないジョブ (NULL) はいくつあってもよい。`findByExecutedFile` は順序や件数で 1 件を選ばなくなったため、
075 を適用していない DB に重複があると、その executed file の reset/コールバック/アップロードは複数行のエラーで止まる
(どちらかのジョブを推測して使うことはない)。075 の適用後は、別のジョブに紐づいた executed file への
`ProcessingJobCommand.link` は "Executed file N is already linked to another job" で失敗する。`link` 自身は例外を
投げるだけで、登録を取り消さない。`process` のパイプラインでは登録と `link` が同じロールバックの段階にあるため、
その段階のロールバック (program と executed_file の削除) が走り、それが成功すればジョブは RETRYABLE、失敗すれば
CLAIMED のまま登録が残る。パイプライン以外から `link` を呼ぶ場合は、呼び出し側が自分の登録を後始末する必要がある。

適用前に、すべての processor/コールバックを停止し、重複がないことを確認する:

```sql
SELECT executed_file_id, COUNT(*) AS jobs, GROUP_CONCAT(id ORDER BY id) AS job_ids
FROM processing_job WHERE executed_file_id IS NOT NULL
GROUP BY executed_file_id HAVING COUNT(*) > 1;
```

結果が空なら 075 を適用する (`IF EXISTS`/`IF NOT EXISTS` なので再実行しても安全)。重複がある場合、075 の ALTER は
全体が失敗し、行もインデックスも変更しない。`ALTER IGNORE` や行の削除で通してはならない。重複ごとに、行をバックアップし、
各ジョブの `state`/`callback_claim`/`input_path`/`updated_at`、`upload_manifest` (`job_id`)、`processing_attempt` の履歴、
`executed_file` の行が実在するかを確認して、その登録を実際に所有するジョブを 1 つ決める。所有していないジョブが
RETRYABLE なら、`retry()` がいずれ行うのと同じく紐づけだけを外す:

```sql
UPDATE processing_job SET executed_file_id=NULL, updated_at=UTC_TIMESTAMP(6)
WHERE id=@job_id AND executed_file_id=@executed_id AND state='RETRYABLE' AND callback_claim IS NULL;
```

それ以外の状態 (CLAIMED、SUBMISSION_UNKNOWN、SUBMITTED、COMPLETED、RESETTING など) の重複は、どちらのジョブも
証拠を持ちうるため、推測で紐づけを外したり行を削除したりせず、上記の手順で個別に整合する。重複が残っている間は 075 を
適用せず、その録画を処理しない。

## 既存の圧縮アーカイブ

圧縮は `<分割ファイル>.gz` を排他的に (CREATE_NEW) 作成する。同名のアーカイブがすでにある場合、それを再利用・上書き・
削除することはなく、アップロードもエンコーダへの投入も行わずに "Compressed file ... already exists and is not reused"
で失敗する。NAS には何も届いていないため、分割と登録はロールバックされ、ロールバックが完了すればジョブは RETRYABLE に
なるが、アーカイブが残っている限り再実行のたびに同じ理由で失敗する。今回の試行が作成したアーカイブ
(排他的に開けたもの) だけがロールバックで削除される。存在の確認の後に別の書き込みが同名のアーカイブを作った場合も、
排他的なオープンが `FileAlreadyExistsException` で失敗するため、そのアーカイブは今回の試行のものと見なされず、
同じように残され、アップロードも投入も行われない。

整合: processor を停止し、そのアーカイブを参照する `upload_manifest` (`source_path`) や created_file がないかを確認する。
参照があれば、それは以前のアップロードの証拠なので、そのジョブ/マニフェストの手順 ([verified-uploads.md](verified-uploads.md))
で整合する。参照がなく、出所が不明なら、削除せずに別の場所へ移して保管し、その後 `process` を再実行する。

## ブロックされたジョブの診断 (runbook)

いずれも、先に対象の録画のすべての `process`/`after-encode`/`reset`/エンコーダの後処理を停止し、
`tvpcli status --job-id <id>` (または `SELECT * FROM processing_job WHERE input_path = ?`) と `tvpcli history --job-id <id>` で
現在の状態と最後の段階を確認する。`updated_at` の古さはオーナーが死んだ証拠にならない。

CLAIMED と SUBMISSION_UNKNOWN の SQL による遷移は、この runbook の後の
[オペレーターの手順](#オペレーターの手順-暗黙の再投入なし)にある。CLAIMED のジョブを `process` の再実行が自動で
再開することはない (再実行は同じメッセージで止まる)。

- **`Job N is CLAIMED (...): another invocation is processing it, or an earlier one stopped after its upload started or without finishing rollback`**:
  実行中のプロセスがないことを確認し、`history` で最後の段階を見る。
  - UPLOAD_ORIGINAL 以降まで進んでいた場合は、アップロードとジョブの両方を整合する。`tvpcli release-claim <path>` が
    クリアするのは放棄された `upload_manifest` のクレームだけで、`processing_job` は CLAIMED のまま変わらない。
    1. [verified-uploads.md の復旧手順](verified-uploads.md#復旧と一時ファイルのクリーンアップ)に従い、
       `release-claim` で放棄されたクレームを解放し、マニフェストの状態 (CREATING/FINALIZING など) を整合する。
    2. ジョブは SUBMISSION_UNKNOWN へ遷移していないのでエンコーダのタスクは存在し得ない。それを確認したうえで、
       オペレーターの手順の CLAIMED の項のとおり、ガード付きの UPDATE
       (`UPDATE processing_job SET state='REJECTED', updated_at=UTC_TIMESTAMP(6) WHERE id=@job_id AND state='CLAIMED' AND callback_claim IS NULL;`、
       ちょうど 1 行) で REJECTED にし、確認付きの `tvpcli reset <path>` で登録とアップロード済みの成果物を片付ける。
       reset はクレームや未登録の最終成果物が残っていると止まるので、そのときは手順 1 に戻る。その後に `process` を実行する。
  - それより前で止まっていた場合 (ロールバックの失敗、RETRYABLE の記録の失敗、SPLIT/COMPRESS の履歴の書き込みの失敗) は、
    オペレーターの手順の CLAIMED の項に従う: executed_file の行があれば REJECTED にしてから reset、
    副作用が何も残っていなければ RETRYABLE にする。
  - 致命的な `Error` で CLAIMED のまま止まった場合も、最後の段階で上のどちらかを選ぶ。
- **`Job N is SUBMISSION_UNKNOWN (...): encoder submission may have succeeded`**: エンコーダのキュー/履歴を確認し、
  オペレーターの手順の SUBMISSION_UNKNOWN の SQL で SUBMITTED または REJECTED に整合する。RETRYABLE にしてはならない。
- **`Job N is REJECTED`/`RESETTING (...): finish its reset`**: 表示された `tvpcli reset <path>` を確認付きで実行する。
  RESETTING が残る場合は、オペレーターの手順の RESETTING の説明と
  [verified-uploads.md](verified-uploads.md#復旧と一時ファイルのクリーンアップ)の reset の復旧に従う。
- **`Job N remains quarantined in place`** (警告。終了コード 4): 品質ルールによる保留。
  `tvpcli quality report`/`quality release` ([recording-quality.md](recording-quality.md))。
- **`Changed recording version requires reconciliation of job N (STATE)`**: 同じパスに別のフィンガープリントの
  ジョブがあり、それが RETRYABLE ではない。元のファイルが置き換えられたのか、メタデータが変わっただけなのかを確認し、
  そのジョブをその状態の項 (この一覧の CLAIMED/SUBMISSION_UNKNOWN/REJECTED の項) で整合してから `tvpcli reset <path>` を実行する。
- **`Recording path already registered without a matching job`**: 064 より前の登録、または引き継がれていない従来の
  登録。実行中のエンコードなら[実行中の既存エンコード](#実行中の既存エンコード-従来のジョブの引き継ぎ)の手順で引き継ぎ、
  そうでなければ `tvpcli reset <path>`。
- **`Recording name X already registered`**: 同じファイル名の program が、すでに別のパスの録画として登録されている。
  今回のパスには登録がないので、今回のパスに `reset` をしても衝突は解消しない。既存の登録を
  `SELECT p.id, p.status, e.id, e.file FROM program p JOIN executed_file e ON e.id = p.executed_file_id WHERE p.name = 'X'`
  と `SELECT * FROM processing_job WHERE executed_file_id = <e.id>` で調べる。既存の登録を残す (今回のファイルが重複) なら、
  今回のファイルを監視対象のディレクトリの外へ移す。既存の登録を取り消す場合は、それに関わるエンコードやコールバックが
  動いていないこと、所有するジョブの状態がこの一覧の該当する項で整合済みであることを確認してから、
  **既存の登録のパス** (`e.file`) に対して確認付きの `tvpcli reset` を実行する。reset はその program の生成ファイルと
  NAS 上のコピーも削除する (元の録画は残る)。その後、今回のファイルを `process` する。
- **`Recording already claimed by another invocation`**: 同じバージョンのクレームを別の起動が先に取った。
  その起動が終わるのを待つ。終わっても続く場合は、この一覧の CLAIMED の項として扱う。
- **`Executed file N is already linked to another job`**: 075 の一意制約による拒否。パイプラインはその段階の登録を
  ロールバックしており、成功していればジョブは RETRYABLE、失敗していれば CLAIMED のまま登録が残る
  ([executed file あたり 1 ジョブ](#executed-file-あたり-1-ジョブ-108))。重複の確認クエリと
  `SELECT * FROM processing_job WHERE executed_file_id = N` で既存の紐づけを調べ、整合する。
- **`Compressed file ... already exists and is not reused`**: [既存の圧縮アーカイブ](#既存の圧縮アーカイブ)に従う。
- **`splitted file already exists`**: `tssplitter/` に同じ録画の分割ファイルが残っている。分割ファイル名の接頭辞は
  所有の証拠にならないため削除しない。`splitted_file` の行から参照されているかを確認し、参照されていればその登録を
  reset し、されていなければ別の場所へ移してから再実行する。
- **`Recording changed during inspection` / `Recording changed during quality inspection`**: 書き込み中の録画。
  ジョブは RETRYABLE なので、録画が安定してから再実行するだけでよい。
- **`Processing job N is no longer CLAIMED` / `Cannot link job N to executed file M`**: 実行中に別の操作がジョブを
  変更した。並行して動いている起動や手動の UPDATE を止め、ジョブの状態を読み直してからこの一覧の該当する項に従う。

ジョブの状態: CLAIMED (実行中または中断)、RETRYABLE (安全に再試行できると判明)、
SUBMISSION_UNKNOWN (エンコーダ呼び出しの前に意図をコミット済み)、SUBMITTED (呼び出しが
0 を返した)、COMPLETED (コールバック完了)、REJECTED (エンコーダのタスクがないとオペレーターが確認済み)、
RESETTING (クリーンアップを予約済み)、QUARANTINED (品質ルールで元ファイルをその場に保留中、[recording-quality.md](recording-quality.md))。
`processing_job.state` には `ProcessingJob.State` の定数名がそのまま保存されるため、定数名を変える場合はデータ移行が必要である。
読み取り側は保存されうるすべての状態名を enum 定数として持つ必要がある。未知の値は enum への変換に失敗し、
`status`/`history` を含めその行を読む処理がエラーになる。ロールバックや新旧バージョンの混在運用では、
新しいバージョンが書き込んだ状態が残っていないか、古いバージョンがそれらを読めるかを考慮すること。
0 の戻り値は受理の通知であり、exactly-once の証明ではない。
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
