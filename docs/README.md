# docs

リポジトリ全体（backend / frontend、`processor` / `manager`）に共通するドキュメントを置く。

| ファイル | 役割 |
| --- | --- |
| [ubiquitous-language.md](ubiquitous-language.md) | 用語集。コード・DB・UI・ドキュメントで同じ言葉を同じ意味で使うための辞書 |

## ドキュメントの置き場所

| 場所 | 役割 |
| --- | --- |
| `README.md` (ルート) | プロジェクト概要と、各パートの README への入口 |
| `AGENTS.md` / `CLAUDE.md` | コーディングエージェント向けの規約・コマンド・アーキテクチャ概要 |
| `docs/` (ここ) | backend/frontend をまたぐ横断的なドキュメント |
| `backend/README.md`, `frontend/README.md` | 各パートのセットアップ・設定・デプロイ手順 |
| `backend/docs/` | backend の機能別の運用・設計ドキュメント ([索引](../backend/docs/README.md)) |
| `backend/<module>/README.md` | そのモジュール固有の利用方法 (例: `backend/manager/console/README.md` は CLI の出力形式) |

置き場所の目安: 複数パートにまたがる → `docs/`、backend の一機能に閉じる → `backend/docs/`、単一モジュールに閉じる → そのモジュールの README。
