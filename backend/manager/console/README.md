# Manager CLI の出力

既定では、人が読める形式で出力する。読み取り系のコマンドは `--json` または
`--ids-only` を受け付ける (同時には指定できない)。出力をパイプする場合は、実行可能 jar を直接実行すること。
Gradle 自体がビルドメッセージを標準出力に書き出すため。

```sh
java -jar manager-console.jar search --name ニュース --json
java -jar manager-console.jar get program 123 --json
java -jar manager-console.jar get executed_file 456 --json
java -jar manager-console.jar duplicates --min-overlap 0.8 --json
java -jar manager-console.jar duplicates --ids-only
```

機械向けの標準出力は UTF-8 で、見出し・色・診断メッセージを含まない。ログとエラーは
標準エラー出力に出る。JSON は整数の `schemaVersion: 1` と、以下のペイロードを持つ 1 つのオブジェクトである。
読み取りコマンドのエラー時は、機械向けの標準出力は空になるため、読み取り結果は
プロセスが正常終了した場合にのみ使うこと。doctor の診断失敗は、これとは異なり、
完全なレポートを終了コード 1 で返す。`--help` と `--version` は、通常の
情報テキストの動作を維持する。

| コマンド | ペイロード |
| --- | --- |
| `search --json` | `programs`: `tsExists` を持つ program オブジェクトの配列、整数の `total`、`limit`、`offset` |
| `get program ID --json` | `program`: `createdFiles`、`tags`、`checks` を持つ program オブジェクト |
| `get executed_file ID --json` | `executedFile`: executed-file オブジェクト |
| `duplicates --json` | `groups`: 重複グループのオブジェクトの配列 |
| `doctor --json` | `checks`: 失敗を含む診断チェックの配列 |
| `stats --json` | `stats`: `groupBy`、`recordedFrom`、`recordedTo` (null 許容の文字列)、`totals`、`groups` ([ライブラリ統計](../../README.md#library-statistics) を参照) |

program のフィールド: `id`、`executedFileId`、`drops`、`size` は整数、`duration`
は秒単位の数値、`name`、`status`、`recordedAt`、`channel`、`title`、
`channelName` は文字列である。`size` はバイト数。`status` は `REGISTERED`、`COMPLETED`、
`ERROR` のいずれか。ID とバイト数は 64 ビット整数であり、利用側は
精度を保持しなければならない。`recordedAt` は、オフセットなしの ISO ローカル日時 (`yyyy-MM-ddTHH:mm:ss`。秒は常に含まれ、小数秒は 0 でない場合のみ) で、
既存の録画環境のローカル時刻の慣習 (通常は日本時間) に従う。UTC には
変換されない。`tsExists` は、登録された TS のメタデータがあることを示す真偽値であり、
NAS 上の実際の整合性チェックではない。

`tags` は、録画に付いた検出タグ (`ews`、
`superimpose`) の文字列配列で、`checks` は、その録画に対して実行された検出器
(`emergency_broadcast`) の文字列配列である。`tags` にないタグが「検出されなかった」ことを意味するのは、
その検出器が `checks` に含まれている場合のみで、そうでなければその録画は一度もチェックされていない。

`createdFiles` は、整数の `id`、`splittedFileId`、`size`
(バイト)、文字列の `file`、null 許容の文字列 `mime`、`encoding`、文字列の `status`
(`REGISTERED`、`ENCODE_SUCCESS`、`FILE_MOVED`) を持つオブジェクトを含む。`isTs` などの
補助プロパティは含まれない。`executedFile` は、整数の `id`、`drops`、`size`、数値の
`duration`、文字列の `file`、`recordedAt`、`channel`、`title`、`channelName`、
および `status` (`REGISTERED`、`DROPCHECKED`、`SPLITTED`) を持つ。

各重複グループは、文字列の `reason` (`SAME_BROADCAST` または `OVERLAP`)、整数の
`recommendedId`、整数配列の `candidateIds` (その他のメンバー)、および推奨順の `programs`
(それぞれ `tsExists` を持つ) を持つ。

`--ids-only` は、1 行につき 1 つの program ID を出力する。search は指定されたページを
選択された並び順で出力し、get は指定された program ID を出力する。duplicates は、
**推奨される残す対象を含むすべてのメンバー**を、重複を除いて ID 順に出力する。
削除候補のリストではない。`get executed_file --ids-only` は不正である。
executed-file の ID は program の ID ではないため。

| 終了コード | 意味 |
| --- | --- |
| 0 | 成功。search/duplicates の結果が空 (空の配列、または ID 行なし) の場合も含む |
| 1 | 操作上の失敗。無害化された診断を標準エラー出力に出す (例外のクラス名はログに出るが、メッセージは出ない) |
| 2 | 不正な入力、サポートされないオプションの組み合わせ、または不正な正の ID |
| 3 | 明示的に指定された program/executed file が存在しない |

リソースが存在しない場合の終了コードは、人が読む形式でも適用される。読み取りコマンドは
変更を一切行わない。一部のみ成功したバッチや整合性に関する終了コードは、それらの操作を
サポートするコマンドのために予約されている。

## 環境の診断

```sh
java -jar manager/console/build/libs/console.jar doctor
java -jar manager/console/build/libs/console.jar doctor --json
java -jar manager/console/build/libs/console.jar doctor --check-write --json
java -jar manager/console/build/libs/console.jar --spring.profiles.active=local doctor --json
```

doctor は、接続を試みる前に、必須のデータベース/NAS の各設定を検証する。
manager と同じ Boot のアプリケーション設定、core/console/infrastructure の
プロファイル、環境変数、外部のプロファイルによる上書きを読み込むが、
Spring のアプリケーションコンテキスト、データソースのプール、通常のコマンドグラフは作成しない。
そのため、不正な URL や利用できないデータベースも診断できる。Spring の
設定オプションは `--spring.property=value` を使い、`doctor` の前後どちらにも置ける。
`doctor --help` は、有効な設定やネットワークアクセスを必要としない。

各チェックは `id`、`status` (`PASS`、`FAIL`、`UNCHECKED`)、対処可能な
`reason` を持つ。設定値、ユーザー名、パスワード、URL、生の例外は
出力されない。

チェックの id は JSON を利用する側にとって安定している:

| Id | 意味 |
| --- | --- |
| `config.<property>` | 必須の設定 1 つ。例: `config.spring.datasource.url`、`config.samba.video-store-nas.base-dir` |
| `config.load` | 設定ファイルを読み込めなかった |
| `database.connectivity` | 接続して `SELECT 1` を実行 |
| `nas.<store>.read` | ベースディレクトリが存在し、一覧取得できる (`<store>` は `video-store-nas` または `original-store-nas`) |
| `nas.<store>.write` | `--check-write` のプローブ。フラグなし、または読み取りに失敗した後は `UNCHECKED` |
| `nas.<store>.cleanup` | 書き込みプローブの削除 (`--check-write` の場合のみ) |
| `nas.<store>.operation` | 読み取りチェックがすでに通った後の NAS の失敗 |

データベースの URL は、単一ホストの `jdbc:mariadb://host[:port]/database` でなければならない。
複数ホストのフェイルオーバー URL は不正として報告される。doctor は自身のプロセス内でライブラリのログを抑制し、ドライバの
メッセージから認証情報が漏れないようにする。通常のコマンドのログには影響しない。

データベースの診断では、別の接続を使い、その唯一のステートメントは `SELECT 1` である
(接続にはドライバへのヒントとして読み取り専用のフラグも付く)。接続/ソケット/クエリの
タイムアウトは 3 秒で、設定済みの URL にあるタイムアウトを置き換える。
空のデータベースパスワードは、パスワードなしのユーザーのために受け付けられる。認証は
接続チェックで検証される。NAS の診断では、本番の Samba クライアントと同じ SMB2/3 の
ダイアレクト範囲を使い、3 秒の接続・応答・ソケットのタイムアウトと 1 回のリクエスト再試行を加える。これらは I/O ごとの
上限であり、コマンド全体の期限ではない。OS のホスト名解決や複数のチェックで
時間が加算されることがある。他のエンドポイントが失敗しても、両方の NAS ストアがチェックされる。

既定のチェックでは、設定された各 NAS のベースディレクトリについて、存在とディレクトリ一覧の
権限を確認し、録画の読み取りやライブラリのレコードの書き込みは行わない。
ベースディレクトリが空の場合は共有のルートが選ばれる。絶対パス、親ディレクトリへの
トラバーサル、URL のエスケープ/クエリ/フラグメントの文字、制御文字は不正である。
NAS の書き込みチェックは、既定では明示的に `UNCHECKED` となる。
`--check-write` は、各ベースディレクトリの下に UUID 名の `.tsvideos-doctor-*.tmp` ファイルを排他的に作成し、
小さなマーカーを書き込んで読み取り、検証が失敗しても `finally` ブロックで自分のプローブを削除する。
録画を上書きしたり、ベースディレクトリを作成したりすることはない。クリーンアップの失敗は別の `FAIL` となり、
手動で削除するための一時ファイル名のみを示す。作成の失敗は既存ファイルを削除する根拠にならない。
その場合、クリーンアップは `UNCHECKED` となり、接続の失敗前にサーバーが作成リクエストを受け付けていた場合に
確認できるよう、プローブ名が提供される。残っている
`.tsvideos-doctor-*.tmp` ファイル (例: プローブ中にプロセスが kill された後) は、
マーカーだけを含んでおり、安全に削除できる。

終了コード `0` は失敗したチェックがないこと (任意の未チェックの書き込みプローブは許容される)、
`1` は 1 つ以上の診断が失敗したこと、`2` は CLI の入力が不正であることを意味する。JSON の
診断レポートは、終了コード `1` でも標準出力に出力される。設定ファイルの
読み込みに失敗した場合は、無害化された `config.load` チェックが生成される。doctor は
`--ids-only` を受け付けない。その結果はインフラのチェックであり、program の ID ではないため。

## 検索フィルタとページネーション

すべてのフィルタは任意で、AND で組み合わされる。フィルタなしの `search` は
最新の録画を一覧する。既存の `search -n NAME` の構文も引き続きサポートされる。

| オプション | 意味 |
| --- | --- |
| `-n`, `--name` | program 名の大文字小文字を区別しないリテラルの部分文字列。`%` と `_` は通常の文字 |
| `--channel` | 正確なチャンネル識別子 (表示用のチャンネル名ではない) |
| `--recorded-from` | 境界を含む ISO ローカル日付または日時。例: `2026-10-01`、`2026-10-01T06:00:00`。日付はその日の 0 時を意味する |
| `--recorded-to` | 境界を含まない ISO ローカル日付または日時。日付はその日の 0 時を意味するため、1 日全体を含めるには翌日を指定する |
| `--status` | `registered`、`completed`、`error` |
| `--min-drops`, `--max-drops` | 境界を含む、0 以上のドロップフレーム数の上下限。不明なドロップ数 (`-1`) は数値の上下限に一致しない |
| `--file-type` | 以下で定義される `ts` または `encoded` |
| `--sort` | `recorded-at` (既定)、`duration`、`size` |
| `--direction` | `desc` (既定) または `asc`。同じ並び順で program ID が同点を解消する |
| `--limit` | ページサイズ。1〜500、既定は 100 |
| `--offset` | スキップする一致した program の数。既定は 0、0 以上でなければならない |

日付の範囲は順序が正しい (`from < to`) 必要があり、ドロップ数の上下限も順序が正しい
(`min <= max`) 必要がある。日付は録画環境のローカル時刻の慣習を維持する。
size/duration による並べ替えは、生成ファイルの合計サイズではなく、元の
executed-file のメタデータ (バイト/秒) を使う。
元のメタデータが欠けている program は、名前/ステータスで、またはフィルタなしで検索できる。
SQL の null の並べ替え値は、昇順では既知の値より前に、降順では後に来て、
ID による同点解消は同じである。日付/チャンネル/ドロップ数/ファイル種別のフィルタには
一致しない。既存のドメインのフォールバック値は出力でも変わらない
(`drops: -1`、`duration: -1`、`size: 0`、
空文字列、最小のローカル日時)。

ファイルのフィルタには、`FILE_MOVED` 状態の登録済み created file が必要になる。`ts` は
MIME `video/vnd.dlna.mpeg-tts` に一致する。`encoded` は、その TS の MIME を除く `video/*` に一致し、
MIME が null のものや付随ファイルは対象にならない。両方の種別を持つ program はどちらのフィルタにも
一致し、一致するファイルが複数あっても program が重複したり合計が水増しされたりしない。
これはメタデータ上の存在であり、NAS 上の実際のプローブではない。

JSON は `schemaVersion: 1` と `programs` 配列を維持し、`total` (ページネーション前の
一致数の合計)、`limit`、`offset` を追加する。人が読む形式の出力では、
表の後に同じ件数を標準エラー出力へ出す。ID のみの出力は
選択されたページの ID のみを含む。後続のページを取得するには、`--offset` を `--limit` ずつ増やす
(または JSON の `total` を使う)。空のページや範囲外のページも成功する。別々のページリクエストは
ライブラリの変更を反映することがある。安定した同点解消により、各スナップショット内での
並び順の曖昧さは避けられる。

互換性: search は以前、最大 500 件の program を一度に、古い順で返していた。
現在は新しい順 (`--direction asc` で従来の順に戻る)、ページサイズ 100 が既定のため、
それ以上取得するには `--limit 500` を渡すか、`--offset` でページ送りする。
短期間だけ存在した JSON の `truncated` フラグは `total` に置き換えられた。
`offset + programs.length < total` の場合、そのページは不完全である。
`--ids-only` は、さらに一致する program がある場合に、次の `--offset` を示す警告を
標準エラー出力に出す。

```sh
java -jar manager-console.jar search --channel BS1 --status completed --max-drops 0 --json
java -jar manager-console.jar search --recorded-from 2026-10-01T00:00:00 --recorded-to 2026-11-01T00:00:00 --file-type encoded --sort size --direction desc --limit 50 --offset 50 --json
```

共有のバックエンド API は、`ProgramCommand.search(ProgramSearchQuery)` で、
`ProgramSearchPage(items, total, limit, offset)` を返す。検証、フィルタリング、並べ替えの
セマンティクスは core にあり、Web インターフェースが再利用する (Web の手動コレクション/タグの
絞り込みは `LibraryProgramSearchCommand` が同じ条件に加える。CLI にはこの絞り込みがない)。以前の
`selectByName` メソッドは既存の呼び出し元のために残されている。新しい検索の呼び出し元は
共有のページング API を使うこと。
