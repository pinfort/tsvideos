# 重複レビューの非表示 (意図した重複)

重複レビュー (`GET /api/v1/programs/duplicates`、Web の `/duplicates`) で、意図して残している重複だと確認したグループを非表示にできる (#194)。非表示は元に戻せ、DB (`library_duplicate_dismissal`、library 所有) に保存されるので、再読み込みや manager:api の再起動の後も残る。

## マイグレーション

この機能を含む manager のビルドをデプロイする前に、既存の DB へ `database-schema/src/main/resources/db/migrations/078_library_duplicate_dismissal.sql` を一度適用する (077 の後。`IF NOT EXISTS` なので再実行しても安全)。追加するのは `library_duplicate_dismissal` テーブルだけで、既存のテーブルは変更しない。processor は参照しないので、processor の停止は不要。Testcontainers のスキーマと `docker-compose.test.yml` は同じファイルから生成/マウントする。

## グループの同一性

- 非表示の単位は「検出理由 (`SAME_BROADCAST` / `OVERLAP`) + メンバーの番組 ID の集合」。ID は昇順に並べたカンマ区切り (`1,2,3`) で保存し、生成列 `group_key` (その SHA-256) に一意索引を張る。
- 推薦 (`recommendedId`) や並び順は同一性に含めない。ドロップ数・状態・長さ・サイズが変わって残す候補が入れ替わっても、非表示のまま。
- メンバーが増えた・減った、または検出理由が変わったグループは、保存済みの行と一致しないので再びレビューに表示される。`program_ids` には `program` への FK を付けない (メンバーを 1 件削除したときに、保存済みの同一性が別の小さいグループに縮んで一致してしまわないようにするため)。一致しなくなった行は残るが、表示には影響しない。
- 番組を作り直した (reset して再処理した) 場合は番組 ID が変わるので、別のグループとして表示される。

## API

| メソッド・パス | 内容 |
| --- | --- |
| `GET /api/v1/programs/duplicates?includeHidden=false` | 既定では非表示のグループを除く。`includeHidden=true` で非表示のグループも返す。各グループに `hidden` (boolean) が付く。正式な入力値は `true`/`false`。boolean として解釈できない値は 400 |
| `POST /api/v1/programs/duplicates/hide` | 本文 `{"reason":"SAME_BROADCAST","programIds":[1,2]}` のグループを非表示にする |
| `POST /api/v1/programs/duplicates/restore` | 同じ本文のグループを再表示する |

- hide / restore は、現在の検出結果に**ちょうどその**グループ (同じ理由・同じメンバー) があるときだけ受け付ける。ID の順序は問わない。応答は `{"reason":...,"programIds":[昇順],"hidden":true|false}`。
- 冪等: 非表示のグループをもう一度 hide しても、表示中のグループを restore しても成功し、行は増減しない (最初に非表示にした時刻 `dismissed_at` は変わらない)。
- 400: 本文が JSON でない、`reason` がない/未知、`programIds` がない/配列でない/`null` を含む、2 件未満、正でない ID、重複した ID。
- 409: 指定したグループが現在検出されない (一部のメンバーだけ・余分なメンバーを含む・理由が違う・メンバーが変わった・存在しない ID)。レビューを再読み込みして確認し直す。ロック待ちのタイムアウト/デッドロックも 409 で、再試行できる。
- 判定と書き込みは 1 つの `REPEATABLE_READ` トランザクションで行う。録画・番組・ファイル・NAS には一切触れない。

## 影響範囲 (非表示を反映するもの・しないもの)

非表示は**重複レビューの表示だけ**に効くラベルで、残す方針 (keep policy) ではない。

| 利用箇所 | 非表示の扱い |
| --- | --- |
| `GET /api/v1/programs/duplicates` (既定) | 除く |
| Web `/duplicates` | 非表示のグループは「非表示にした重複グループ」の欄に分けて表示し、そこから再表示できる。削除対象には選べず、非表示にしたグループのメンバーは一括削除の選択から外れる (再表示しても選択は戻らない) |
| 重複検出 `DuplicateProgramCommand.detect` | 変わらない (検出の判定は同じ) |
| `tvmcli duplicates` | 除かない (検出結果をそのまま出す) |
| `tvmcli cleanup plan --duplicates` (`CleanupPlanCommand`) | 除かない。`DuplicateReviewCommand.review(includeHidden = true)` を使うので、非表示のグループも整理候補に入る。整理プランは人が JSON を確認してから適用するので、意図した重複を残すには `--keep` や `--channel` で調整するか、対象から外す |
| `GET /api/v1/storage` / `/storage` の重複の整理候補と見積もり | 除かない (`detect` を直接使う) |

CLI と保存領域の概要を非表示に合わせる場合は、整理の対象が黙って変わらないよう、別の Issue で明示的なオプションとして扱う。

## 実装

- `library` の `DuplicateReviewCommand` (`review(includeHidden)`、`hide`、`restore`)、ドメイン型 `DuplicateGroupIdentity`、例外 `StaleDuplicateGroupException`。永続化は internal の `DuplicateDismissalMapper` (台帳テーブルを読まない)。
- `manager:api` の `DuplicateReviewController` は入力の検証と HTTP ステータスの対応付けだけを行う。
- Web は `frontend/lib/api/duplicate-dismissals.ts` の Server Action (`setDuplicateGroupHidden`) から呼び、成功したら `router.refresh()` で再取得する。
