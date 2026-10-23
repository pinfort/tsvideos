# ts-videos

TS抜きした録画データを処理、管理するためのアプリケーション

- backend: 録画データの処理・管理を行う Kotlin/Spring Boot バックエンド（storage/catalog/library/processing などの業務モジュールと manager/processor の実行アプリで構成） ([backend/README.md](backend/README.md))
- frontend: manager 向けの Web フロントエンド ([frontend/README.md](frontend/README.md))

## ドキュメント

- [docs/](docs/README.md): 横断的なドキュメント (用語集など) と、ドキュメントの置き場所の方針
- [backend/docs/](backend/docs/README.md): backend の機能別の運用・設計ドキュメント

## ローカルでのバックエンド動作確認

`docker-compose.test.yml` で MariaDB と Samba (NAS) を起動すると、環境変数なしで `manager:api` をローカル起動できます。詳細は [backend/README.md](backend/README.md) を参照してください。

```bash
docker compose -f docker-compose.test.yml up -d
cd backend && ./gradlew manager:api:bootRun
```

## ローカルでのフロントエンド動作確認

上記の `manager:api` を `http://localhost:8080` で起動しておけば、`frontend` はデフォルト設定のまま接続できます。詳細は [frontend/README.md](frontend/README.md) を参照してください。

```bash
cd frontend
pnpm install
pnpm dev
```
