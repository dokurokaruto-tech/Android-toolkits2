# PC生成エージェント

Android Toolkits から生成依頼を受け取り、Stable Diffusion WebUI / Forge の API を呼び出す **PC側の永続キュー** です。

- 依頼をSQLiteへ保存してからAndroidへ受付完了を返します。
- Androidアプリを閉じても、指定枚数までPC側で生成を続けます。
- 完成画像はPCの `generated/YYYY-MM-DD/`（変更可能）へ日付別に保存します。
- Androidの既存の「閲覧」ボタンから日付フォルダ・画像を表示できます。
- 選択画像はAndroid端末へダウンロードして「全画像」に取り込めます。
- エージェントを途中で終了しても、次回起動時に未完了キューを再開します。

## 必要なもの

1. Windows 10 / 11
2. Python 3.10以降（追加Pythonパッケージは不要）
3. APIを有効にした Stable Diffusion WebUI / Forge

A1111なら通常は `webui-user.bat` の `COMMANDLINE_ARGS` に `--api` を追加します。ForgeでもAPIが利用可能な状態にしてください。

## 最短の起動手順

1. Stable Diffusionを起動する。
2. `start-agent.bat` をダブルクリックする。
3. 初回に `config.json` が自動作成される。
4. SD APIが標準の `http://127.0.0.1:7860` でない場合だけ、`config.json` の `sd_base_url` を直して再起動する。
5. Windows Defender Firewallの確認が出たら、使用するネットワーク（通常はプライベート、またはTailscale）だけ許可する。
6. 黒い画面に次のような行が表示されるので、その**実際に表示された数値をそのまま**Androidアプリへ入力する。

```text
ANDROID APP URL (use this exact value): http://192.168.1.23:3001
```

PCごとにルーターから割り当てられるIPは異なるため、`192.168.1.23` の部分は例ではなく、起動時にエージェント自身が検出して決定します。同じ内容は `connection-info.txt` にも自動保存されます。複数のネットワークがある場合は、同じWi-Fiなら先頭の推奨URL、Tailscaleなら `100.x.x.x:3001` と表示された候補を使います。

## 接続に使う数値（ソフト側で固定済み）

| 用途 | 値 |
|---|---|
| Stable Diffusion API（PC内部） | `http://127.0.0.1:7860` |
| PC生成エージェントのポート | `3001` |
| Androidから入力するURL | 起動時の `ANDROID APP URL` に表示された具体的なIP + `:3001` |

`127.0.0.1` はPC自身を表すため、Android側には入力しないでください。

## 設定

`config.example.json` から初回に作られる `config.json` を編集します。

```json
{
  "listen_host": "0.0.0.0",
  "listen_port": 3001,
  "sd_base_url": "http://127.0.0.1:7860",
  "output_dir": "generated",
  "database_path": "data/agent.sqlite3",
  "api_key": "",
  "request_timeout_seconds": 600,
  "retry_count": 1,
  "legacy_api_url": ""
}
```

| 項目 | 意味 |
|---|---|
| `listen_host` | Androidから接続するため通常は `0.0.0.0` |
| `listen_port` | エージェントのポート。標準は `3001` |
| `sd_base_url` | 同じPCで動くSD WebUI / Forge API |
| `output_dir` | 完成画像の保存先。絶対パスも使用可能 |
| `database_path` | 永続ジョブキューと状態の保存先 |
| `api_key` | 任意の接続キー。設定した場合はAndroidにも同じ値を入力 |
| `request_timeout_seconds` | 1枚に許可する最大通信時間 |
| `retry_count` | 1枚の生成に失敗した場合の再試行回数 |
| `legacy_api_url` | 既存の `/api/generate-prompt` サーバーを併用するときだけ、そのURLを指定 |

`config.json`、生成画像、SQLite、プロンプトを含むメタデータは `.gitignore` 対象です。

## 保存構成

```text
pc-generation-agent/
├─ generated/
│  ├─ 2026-08-22/
│  │  ├─ GEN_20260822_153012_123_ab12cd34_0001.png
│  │  └─ ...
│  └─ 2026-08-23/
├─ data/
│  ├─ agent.sqlite3
│  └─ metadata/YYYY-MM-DD/*.json
└─ config.json
```

`output_dir` の日付フォルダに手動で置いた PNG/JPEG/WebP も、Androidの閲覧一覧に現れます。

## API概要

- `GET /api/v1/health` — 接続・SD到達確認
- `POST /api/v1/jobs` — 複数画像を永続キューへ一括登録
- `GET /api/v1/jobs/{id}` — 状態・枚数・進捗
- `POST /api/v1/jobs/{id}/stop-after-current` — 現在の1枚の完了後に停止
- `POST /api/v1/jobs/{id}/cancel` — 強制中断
- `POST /api/v1/jobs/{id}/skip` — 現在の画像をスキップ
- `GET /api/v1/library/dates` — 日付フォルダ一覧
- `GET /api/v1/library/images?date=YYYY-MM-DD` — 画像一覧
- `GET /api/v1/files/{date}/{name}` — 完成画像
- `/sdapi/v1/*` — 既存のサムネイル生成・進捗機能を壊さないためSD APIへ中継

## 注意

- PCがスリープ・休止・シャットダウン中は生成できません。復帰してエージェントを再起動すると未完了キューを再開します。
- エージェントとSD WebUI / Forgeの両方を起動しておく必要があります。
- インターネットへ直接ポート公開しないでください。LANまたはTailscale内で使い、必要なら `api_key` も設定してください。
- エージェントが見つからない場合、Androidアプリは互換性のため従来のSD直結生成へフォールバックします。その場合はPC永続保存ではなく端末保存です。
