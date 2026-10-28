# docs

リポジトリ全体（backend / frontend、`processor` / `manager`）に共通するドキュメントを置く。

| ファイル | 役割 |
| --- | --- |
| [architecture.md](architecture.md) | システム全体の構成。コンポーネント、モジュールの責務と依存方向、処理の流れとロールバック境界、永続化、テスト戦略、再編の履歴 (Mermaid の図付き) |
| [ubiquitous-language.md](ubiquitous-language.md) | 用語集。コード・DB・UI・ドキュメントで同じ言葉を同じ意味で使うための辞書 |
| [agent-workflow.md](agent-workflow.md) | Codex が管理・レビュー、Claude Code が実装を担当する作業手順 |

## ドキュメントの置き場所

| 場所 | 役割 |
| --- | --- |
| `README.md` (ルート) | プロジェクト概要と、各パートの README への入口 |
| `AGENTS.md` / `CLAUDE.md` | コーディングエージェント向けの役割分担・コマンド・開発規約。アーキテクチャの説明は持たず [architecture.md](architecture.md) を参照する |
| `docs/` (ここ) | backend/frontend をまたぐ横断的なドキュメント |
| `backend/README.md`, `frontend/README.md` | 各パートのセットアップ・設定・デプロイ手順 |
| `backend/docs/` | backend の機能別の運用・設計ドキュメント ([索引](../backend/docs/README.md)) |
| `backend/<module>/README.md` | そのモジュール固有の利用方法 (例: `backend/manager/console/README.md` は CLI の出力形式) |

置き場所の目安: 複数パートにまたがる → `docs/`、backend の一機能に閉じる → `backend/docs/`、単一モジュールに閉じる → そのモジュールの README。

## 言語

人が読むドキュメント (各 README、`docs/`、`backend/docs/`) は日本語で書く。`AGENTS.md` / `CLAUDE.md` はエージェント向けの指示のため英語で書く。コードの識別子、CLI のフラグ、コードブロック、SQL、ドメイン用語 (録画、番組など) は言語を問わずそのまま記載する。
