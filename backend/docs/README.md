# backend/docs

backend の機能別の運用・設計ドキュメント。マイグレーション適用順、運用上の注意、障害時のリカバリ手順など、README に書くには長くなる機能単位の説明を置く。セットアップと環境変数は [../README.md](../README.md) を参照。

| ファイル | 対象 | 役割 |
| --- | --- | --- |
| [processing-idempotency.md](processing-idempotency.md) | processor | 処理ジョブの同一性 (job key) と再実行・復旧 (reconciliation) |
| [processing-preflight.md](processing-preflight.md) | processor | `tvpcli preflight` による事前チェック (入力・ツール・容量・NAS) |
| [processing-history.md](processing-history.md) | processor | `tvpcli status` / `history` と処理試行の履歴 |
| [verified-uploads.md](verified-uploads.md) | processor | NAS への検証付きアップロード (`upload_manifest`) と所有権・復旧 |
| [graceful-shutdown.md](graceful-shutdown.md) | processor | 停止シグナル受信時の協調的な中断、猶予時間 (`PROCESSOR_SHUTDOWN_GRACE_SECONDS`) と中断後の状態 |
| [bulk-library.md](bulk-library.md) | manager | 一括削除・移動 (プレビュー → 適用) の仕様 |
| [library-health.md](library-health.md) | manager | `/health` ダッシュボード、明示的な NAS 検証の実行・保存と、RUNNING のまま残った実行の復旧 |

リポジトリ全体に関わる文書は [../../docs/](../../docs/README.md) に置く。
