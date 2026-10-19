# Codex と Claude Code の分担

ユーザーは Codex に作業を依頼する。Codex は調査、作業分割、受入条件の定義、Claude Code の起動、レビュー、検証、最終報告を担当する。Claude Code は実装、テスト、関連ドキュメントの更新を担当する。

この構成はリポジトリの指示ファイルとローカルの Claude Code CLI による運用であり、常駐サービスではない。Codex の内蔵サブエージェントを Claude に変更する設定ではない。

## 前提

- `claude` が PATH にあり、`claude auth status` でログインを確認できること。
- 未ログインの場合はユーザーの端末で `claude auth login` を実行する。
- モデルは既存の Claude Code 設定を使用する。
- Claude の実行にも、そのアカウントの利用枠が適用される。

## 作業の流れ

1. Codex は `git status --short` と既存差分を確認する。
2. `.agent-work/<task-id>.md` に下記の形式で具体的な作業指示を書く。このディレクトリは Git 管理外とする。秘密情報は含めない。
3. リポジトリルートから Claude Code を起動する。共有するのは指示書と作業ツリーであり、Codex の会話が自動転送されるわけではない。必要なユーザー要件を指示書に明記する。
4. Claude の実行が終わるまで、同じ作業ツリーで別の実装を始めない。長い実行は Codex の実行セッションを保持して進捗を確認する。
5. Codex は `git diff` と新規ファイルを読み、受入条件とテスト結果を検証する。指摘があれば修正指示書を渡し、再度 Claude を起動する。
6. 必要なチェックが成功したら、Codex が変更内容と残る制約を報告する。実行できなかったチェックは明示する。

PowerShell からの委任例（`<task-id>` は実際の識別子に置き換える）:

```powershell
claude -p 'Read CLAUDE.md and .agent-work/<task-id>.md. Implement the assigned task and report results in Japanese.' --permission-mode acceptEdits --output-format json
```

`acceptEdits` はファイル編集を許可するモードで、すべてのコマンドを無条件に許可するものではない。非対話実行で権限が不足した場合は、出力に含まれる拒否を Codex が確認し、必要な操作だけを既存の承認手順で扱う。権限を一括回避するフラグは使用しない。認証失敗、利用枠超過、コマンド失敗時には原因を報告する。

JSON の結果は必要に応じて `.agent-work/` に保存する。終了コードだけでなく、結果本文と権限拒否も確認する。修正依頼では元の指示とレビュー指摘を明記して新しい実行に渡す。別作業との混同を避けるため、暗黙に直近セッションへ継続する `--continue` は使わない。

## 作業指示書の形式

```markdown
# 作業名

## 目的
ユーザーが求める結果と背景。

## 変更範囲
対象ファイル・機能、変更しない範囲、既存の未コミット変更。

## 受入条件
- 観測可能な振る舞いと必要な境界条件。

## 検証
- 実行するコマンドと期待する結果。

## 報告
変更ファイル、動作の変更、実行したチェックと結果、未解決事項。
コミット・push・デプロイは行わない。
```

## 指示の読み込み

Codex はルートの `AGENTS.md` を読み、Claude Code は `CLAUDE.md` から共通の `AGENTS.md` を参照する。既存セッションでは変更後の指示を読み直すか、新しいセッションを開始する。

Codex の指示ファイルの仕組み: [公式ドキュメント](https://learn.chatgpt.com/docs/agent-configuration/agents-md)。Claude の CLI オプションはインストール済みバージョンの `claude --help` で確認できる。
