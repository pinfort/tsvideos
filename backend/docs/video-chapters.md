# 動画のチャプタ

Amatsukaze が出力する mp4 には join_logo_scp 由来のチャプタが埋め込まれている。ブラウザの `<video>` は mp4 の埋め込みチャプタ (Nero `chpl` / QuickTime chapter track) を JS にも UI にも公開しないため、processor が ffprobe でチャプタを読み取って DB に保存し、manager:api が JSON で返し、フロントエンドのプレーヤーがチャプタ一覧を表示する (#154)。

VTT ファイルを NAS に生成する方式 (検証付きアップロード・一括移動/削除・reset・verify/health の対象が増える) と、manager:api で mp4 を JVM 内パースする方式 (MP4 パーサの保守と、moov が末尾にある場合の NAS オフセット読み出しが必要) は採らない。

## 保存形式

マイグレーション [074_created_file_chapter.sql](../database-schema/src/main/resources/db/migrations/074_created_file_chapter.sql) (073 の後に適用。`IF NOT EXISTS` なので再実行可能)。所有は `catalog`。

| 列 | 内容 |
| --- | --- |
| `created_file.chapters_probed` | チャプタを取得済みか。`BOOLEAN NOT NULL DEFAULT FALSE` |
| `created_file_chapter.created_file_id` | `created_file(id)` への FK (`ON DELETE CASCADE`) |
| `created_file_chapter.chapter_index` | 0 始まりの開始順の番号。PK は `(created_file_id, chapter_index)` |
| `start_seconds` / `end_seconds` | 秒 (`DOUBLE`) |
| `title` | ffprobe の `tags.title` を加工せずに保存する (`TEXT`)。無いときは NULL |

title は `A`, `A`, `CM`, `B`, ..., `D90Sec`, `CM?`, `G5Sec` のような機械的なラベルで、一意ではない。CM はカットされていないので CM 区間もチャプタになる。1 番組で十数個、5 秒程度の短いチャプタも含まれる。

外部キーの `ON DELETE CASCADE` により、`created_file` を消す操作 (`tvmcli delete`、一括削除、cleanup apply、`tvpcli reset`) ではチャプタの行も一緒に消える。一括移動はパスを書き換えるだけなのでチャプタはそのまま残る。

### 未取得と 0 件の区別

| `chapters_probed` | チャプタの行 | 意味 |
| --- | --- | --- |
| `FALSE` | なし | 未取得。機能導入前に登録された、または ffprobe に失敗した |
| `TRUE` | なし | 取得済みで、ファイルにチャプタが無い |
| `TRUE` | あり | 取得済み |

フラグとチャプタは同じトランザクションで書く (`CreatedFileCatalog.recordChaptersIfMissing`、`MANDATORY`)。`chapters_probed = FALSE` の行だけを `TRUE` に更新し、更新できた呼び出しだけがチャプタを insert する。同時に記録しようとした 2 つ目の呼び出しは行ロックで待ち、1 つ目のコミット後に何も書かずに `false` を返す。読み出し (`chaptersOf`) はフラグを先に読むので、取得済みなのにチャプタが一部だけ見えることはない。

## 取得

`ffprobe -v error -show_chapters -of json <file>` を実行する (`processing` の `ChapterProbeClient`、`FFPROBE_PATH`、タイムアウト 60 秒)。

- 非 0 終了、JSON 不正 (`chapters` 配列が無い、要素がオブジェクトでない)、タイムアウト、ffprobe が無い場合は `null` (未取得のまま)。中断 (`ProcessingCancellation.isInterrupted`) は再送出する。
- 正規化: `start_time` / `end_time` が数値として読めない・非有限・負のチャプタと `end < start` のチャプタは除外し、開始時刻で安定ソート (同じ開始時刻は ffprobe の出力順) してから index を 0 から振り直す。長さ 0 のチャプタは残す。
- `tags.title` が文字列でないときは title なしとする。

### エンコード後 (`tvpcli after-encode`)

`AfterEncodeRunner` は `MimeTypeComponent.isProbeableVideo` の出力 (非圧縮の `video/*`) だけ、ローカルのファイル (アップロード済みのものとバイト単位で同一) に対して解像度と同様にチャプタを取得し、`VerifiedUploadCommand.register` に渡す。

- 新規登録では `created_file` の insert・チャプタ・アップロード台帳の REGISTERED への遷移が 1 つのトランザクションでコミットされる。
- REGISTERED 済みのコールバックを再実行した場合は、未取得のときだけチャプタを補完する。チャプタは identity check に含めない (取得済みのチャプタを上書きせず、登録内容が食い違う再実行は従来どおり拒否する)。
- 取得失敗はエンコード後処理を失敗させない (未取得として残り、バックフィルで取得できる)。

## API

`GET /api/v1/video/{id}/chapters` (manager:api、DB のみで NAS にはアクセスしない)。

- 再生可能な動画 (`CreatedFileCatalog.findStreamableVideo`、`video/mp4`) でなければ 404。
- 200: `{ "probed": true, "chapters": [{ "index": 0, "startSeconds": 0.0, "endSeconds": 5.739, "title": "A" }, ...] }`
- 未取得なら `{ "probed": false, "chapters": [] }`、取得済みで 0 件なら `{ "probed": true, "chapters": [] }`。

フロントエンドの `/video/{id}/view` は Server Component で取得し、失敗・404・未取得・0 件ではチャプタ一覧を出さない (再生には影響しない)。一覧は「開始時刻 (`h:mm:ss`) + title」のボタンで、title が無いときは「チャプタ N」。クリックすると再生/一時停止の状態を変えずに開始位置へシークし、通常のユーザーシークとして視聴位置を保存する。再生中のチャプタは `aria-current` で強調し、title が `CM` で始まるチャプタは控えめに表示する (表示だけで動作は変えない)。

## 既存ファイルのバックフィル (`tvpcli chapters backfill`)

既存の mp4 にもチャプタが埋め込まれているので、未取得の行を後から取得する。

```bash
tvpcli chapters backfill --video-store-root \\nas\videos --dry-run
tvpcli chapters backfill --video-store-root \\nas\videos
```

- 対象は `mime = 'video/mp4'` かつ `chapters_probed = FALSE` の `created_file` (id 順、100 件ずつ)。
- NAS は smbj でアクセスしているため ffprobe から直接読めない。`--video-store-root` に VIDEO_STORE_NAS の共有ルートに対応するローカル/UNC パスを渡し、`created_file.file` (共有ルートからの相対パス。DB 上のパスは baseDir を含む) を結合して ffprobe にかける。ffprobe と NAS (UNC) の両方に届く Amatsukaze 機での実行を想定する。
- 結合は `/` (と `\`) で区切ったセグメントごとに行う。絶対パス、空・`.`・`..`・ドットだけのセグメント、`:` (ドライブレター・スキーム・NTFS ストリーム)、制御文字を含むパスと、結合結果 (リンクを解決した実パスを含む) がルートの外に出るパスは「パス不正」としてスキップする。
- 誤ったルートを渡した場合に備え、ファイルが存在し、サイズが `created_file.size` と一致する場合だけ ffprobe を実行する。欠損・サイズ不一致はスキップして報告する (DB は変更しない)。
- 1 件ずつ短いトランザクションで `recordChaptersIfMissing` する。冪等で、中断後に再実行すると未取得分だけを処理する。別の実行が先に記録した行は「取得済み (他の実行)」と数え、失敗にはしない。
- SIGINT/SIGTERM は `ProcessingCancellation` に従い、次のファイルに進む前に止まる。ffprobe の実行中に停止要求が来た場合は、取得に成功していてもそのファイルを書き込まずに止まる (ファイルサイズ・実パス確認中の中断も再送出する)。記録済みのファイルは残り、途中までの件数を stderr に出して終了コード 130 で終わる。
- `-d` / `--dry-run` は ffprobe まで実行し、対象と取得結果件数を表示するだけで書き込まない。
- 最後に「取得済み (チャプタあり / 0 件 / 他の実行)・ffprobe 失敗・ファイル欠損 / サイズ不一致・パス不正」の件数を出す。ffprobe 失敗・欠損・サイズ不一致・パス不正が 1 件でもあれば終了コード 1、`--video-store-root` が存在しないディレクトリなど引数の誤りは 2。

ffprobe 失敗のファイルは未取得のまま残るので、原因を取り除いてから再実行する。
