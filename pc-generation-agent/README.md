# PC生成エージェント

Android Toolkits から生成依頼を受け取り、Stable Diffusion WebUI / Forge の API を呼び出す **PC側の永続キュー** です。

- 依頼をSQLiteへ保存してからAndroidへ受付完了を返します。
- Androidアプリを閉じても、指定枚数までPC側で生成を続けます。
- 完成画像はPCの `generated/YYYY-MM-DD/`（変更可能）へ日付別に保存します。
- Androidの既存の「閲覧」ボタンから日付フォルダ・画像を表示できます。
- 閲覧一覧にはPCで480×854以内へ圧縮したモバイル用JPEGサムネイルを配信します。
- サムネイルを開いた全画面表示だけオリジナル解像度の画像を配信します。
- 初回表示はアニメーションではなく、可逆PNGストリップの実データを上から順に受信・描画します。
- 全画面では現在画像の完了後、前後1枚の組、次に前後2枚の組を順番に先読みして停止します。ページをめくるたび同じ範囲だけ追加します。
- 原寸キャッシュは最大約50枚をメモリだけに保持し、アプリ最小化時に破棄します。
- ページを変えたり全画面を閉じても、開始済みの原寸ダウンロードはバックグラウンドで完了してからキャッシュへ入ります。
- 閲覧・全画面表示・生成途中プレビューだけではAndroidへ画像を永続保存しません。
- 「全画像に入れる」を実行した画像だけAndroid端末へダウンロードします。
- 生成時に選んだプロンプトカードのタグは完成画像のメタデータへ保存され、閲覧と仮チャットで使えます。
- 閲覧画面の削除はPC上の完成画像も消します。紐づいた仮チャットも一緒に消えます。
- カード用サムネイルもPCの `thumbnails/YYYY-MM-DD/` に保存します。
- カード用サムネイルも閲覧と同じ圧縮JPEGを配信し、Androidはそれを端末へ保存してオフライン表示します。
- AndroidのCivitaiブラウザから依頼されたモデルをPC側で直接ダウンロードし、Checkpointは `checkpoint_dir`、LoRAは `lora_dir` へ保存します。
- モデルと同じフォルダにプレビュー画像（`<名前>.png` と `<名前>.preview.png`）と `<ファイル名>.civitai.info`（トリガーワード等の覚書）を添えます。
- 保存後はForgeのモデル一覧を更新（refresh-checkpoints / refresh-loras）します。
- エージェントを途中で終了しても、次回起動時に未完了キューを再開します。

## 必要なもの

1. Windows 10 / 11
2. Python 3.10以降（画像圧縮用Pillowは `start-agent.bat` が初回に自動導入）
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
  "thumbnail_dir": "thumbnails",
  "mobile_thumbnail_dir": "data/mobile-thumbnails",
  "progressive_tile_dir": "data/progressive-tiles",
  "database_path": "data/agent.sqlite3",
  "api_key": "",
  "request_timeout_seconds": 600,
  "retry_count": 1,
  "legacy_api_url": "",
  "checkpoint_dir": "C:\\AI\\StabilityMatrix\\Data\\Packages\\stable-diffusion-webui-forge\\models\\Stable-diffusion\\sd",
  "lora_dir": "C:\\AI\\StabilityMatrix\\Data\\Packages\\stable-diffusion-webui-forge\\models\\Lora",
  "civitai_api_key": ""
}
```

| 項目 | 意味 |
|---|---|
| `listen_host` | Androidから接続するため通常は `0.0.0.0` |
| `listen_port` | エージェントのポート。標準は `3001` |
| `sd_base_url` | 同じPCで動くSD WebUI / Forge API |
| `output_dir` | 完成画像の保存先。絶対パスも使用可能 |
| `thumbnail_dir` | プロンプト／プリセットカード用サムネイルのPC保存先 |
| `mobile_thumbnail_dir` | 閲覧一覧向け圧縮サムネイルのPCキャッシュ先 |
| `progressive_tile_dir` | 原寸閲覧を上から実データ順に表示する可逆タイルのPCキャッシュ先 |
| `database_path` | 永続ジョブキューと状態の保存先 |
| `api_key` | 任意の接続キー。設定した場合はAndroidにも同じ値を入力 |
| `request_timeout_seconds` | 1枚に許可する最大通信時間 |
| `retry_count` | 1枚の生成に失敗した場合の再試行回数 |
| `legacy_api_url` | 既存の `/api/generate-prompt` サーバーを併用するときだけ、そのURLを指定 |
| `checkpoint_dir` | CivitaiインポートしたCheckpointの保存先（Forgeのモデルフォルダ） |
| `lora_dir` | CivitaiインポートしたLoRAの保存先（ForgeのLoRAフォルダ） |
| `civitai_api_key` | 早期アクセス等で認証が必要なCivitaiダウンロード用。空でも通常モデルは取得可能 |

`config.json`、生成画像、SQLite、プロンプトを含むメタデータは `.gitignore` 対象です。

## 保存構成

```text
pc-generation-agent/
├─ generated/
│  ├─ 2026-08-22/
│  │  ├─ GEN_20260822_153012_123_ab12cd34_0001.png
│  │  └─ ...
│  └─ 2026-08-23/
├─ thumbnails/
│  └─ 2026-08-22/THUMB_....png
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
- `GET /api/v1/library/images?date=YYYY-MM-DD` — 画像一覧（各画像の tags を含む）
- `GET /api/v1/files/{date}/{name}` — オリジナル完成画像
- `GET /api/v1/mobile-thumbnails/{date}/{name}` — 閲覧一覧用の圧縮JPEG
- `GET /api/v1/progressive/{date}/{name}/manifest` — 原寸画像の可逆ストリップ情報
- `GET /api/v1/progressive/{date}/{name}/{index}` — 上から順に読む原寸PNGストリップ
- `GET /api/v1/thumbnail-files/{date}/{name}` — カード用サムネイル
- `POST /api/v1/model-imports` — CivitaiモデルのPCダウンロードを開始（`download_url`・`filename`・`kind: checkpoint|lora`・任意で `thumbnail_url` / `thumbnail_base64` / `trigger_words` 等）
- `GET /api/v1/model-imports/{id}` — ダウンロード進捗（`bytes_downloaded` / `bytes_total` / `progress`）
- `GET /api/v1/model-imports` — 直近のインポート一覧
- `GET /api/v1/models/checkpoints` — `checkpoint_dir` 内のモデル一覧と使用中モデル
- `GET /api/v1/models/checkpoints/preview?name=...` — モデル横のプレビュー画像
- `POST /api/v1/models/checkpoints/active` — 使用モデルを切替（`{"name": "..."}`、SDの正式タイトルで指定）
- `/sdapi/v1/*` — 既存機能との互換用にSD APIへ中継

## 注意

- PCがスリープ・休止・シャットダウン中は生成できません。復帰してエージェントを再起動すると未完了キューを再開します。
- エージェントとSD WebUI / Forgeの両方を起動しておく必要があります。
- インターネットへ直接ポート公開しないでください。LANまたはTailscale内で使い、必要なら `api_key` も設定してください。
- エージェントが見つからない場合は生成を開始しません。端末への意図しないフォールバック保存は行いません。
- Android側で `HTTP 404` が出る場合はPC側のコードが古いのが原因です。リポジトリを更新してエージェントを再起動してください。

## AIキャラチャットのTTS（任意）

```text
タグに音声を保存 → 返信生成時に音声付きタグを記録
                          ↓
バブルの音声ボタン → 音声付きタグが1つか検査
                          ↓
            本文＋サンプル音声を毎回PCへ送信
                          ↓
             Qwen3-TTS Base → WAV → Androidで再生
```

### PCの準備（RTX 2070）

TTSは未設定なら無効です。画像生成だけの環境にPyTorchは不要です。

1. Python 3.12とCUDA対応NVIDIAドライバーを用意します。
2. `pc-generation-agent` で次を実行します。ForgeのPython環境には入れません。

```bat
py -3.12 -m venv .venv-tts
.venv-tts\Scripts\python.exe -m pip install --upgrade pip
.venv-tts\Scripts\python.exe -m pip install torch==2.7.1 torchaudio==2.7.1 --index-url https://download.pytorch.org/whl/cu126
.venv-tts\Scripts\python.exe -m pip install -r requirements-tts.txt
.venv-tts\Scripts\python.exe -c "import torch; print(torch.cuda.is_available()); print(torch.cuda.get_device_name(0))"
```

3. 既存の `config.json` に以下を追加します。モデルのパスは**配置済みの実際のフォルダー**へ置き換えてください。

```json
{
  "tts_model_dir": "C:\\AI\\models\\Qwen3-TTS-12Hz-1.7B-Base",
  "tts_python": ".venv-tts\\Scripts\\python.exe",
  "tts_timeout_seconds": 1200,
  "tts_unload_sd": true
}
```

これは追加項目の例です。既存のURL・APIキー等を消さないでください。
相対パスは `config.json` のあるフォルダー基準です。`tts_python` を空にするとエージェントと同じPythonを使います。

モデルは重みだけでなく、`config.json`、`generation_config.json`、テキスト用トークナイザー、`speech_tokenizer/` 一式も必要です。公式リポジトリの完全なローカル配置を指定します。モデルをGitへ追加しないでください。

4. `start-agent.bat` を再起動します。
5. Androidの既存のPCサーバーURLを、エージェントのTailscaleアドレス（例：`http://100.x.x.x:3001`）に設定します。APIキーも既存設定を共用します。新しいポートは不要です。接続先を変更した場合は、既存の接続確認で成功先を更新してください。

RTX 2070向けに **FP16 + SDPA** を使用します。BF16・FlashAttention 2は要求しません。1回ごとに子プロセスでモデルをロードし、終了時にVRAMを解放するため、初動には時間がかかります。長文は約200文字ずつ順番に処理します。

`tts_unload_sd: true` では、Forgeに接続できる場合、TTS前にチェックポイントをアンロードし、終了後に再ロードします。API非対応ならエラーになります。Forgeを停止するか、手動でGPUを空けてから `false` にしてください。接続できない場合はTTS単独で動作します。

エージェントの画像生成ジョブとTTSは同時実行しません。画像生成中のTTS要求はHTTP 409で拒否し、TTS中の画像ジョブは待機します。**Forge画面での直接生成・モデル切替や他のGPUアプリまでは排他制御できません。** 同時に操作しないでください。RTX 2070での実測は未実施で、8GB VRAMに収まることや生成速度は保証できません。メモリ不足時はForge自体を終了し、短い本文・サンプルで確認してください。

### アプリでの操作

1. タグ編集の「チャットのサンプルボイス」で音声を選び、保存します。
2. 必要ならサンプルの正確な文字起こしを入力します。未入力なら `x_vector_only_mode`（話者特徴のみ）を使うため、クローン品質が下がる場合があります。
3. そのタグを持つ画像でAIの返信を生成します。
4. 完成したバブルの、コピー・再生成ボタンの左にある音声アイコンを押します。
5. 生成完了後に自動再生します。再生中のボタンで停止します。もう一度押すと再生成します。

- サンプル：WAV / FLAC / MP3、1〜30秒（3秒以上推奨）、6 MiB以下。M4A等はWAVへ変換してください。利用許可のある、単一話者の明瞭な音声を使ってください。
- 本文：2,000文字以下。返信候補・画像タグ・思考タグは読み上げから除きます。
- サンプルはアプリ専用領域へコピーするため、選択元のファイルを移動しても使用できます。毎回PCへ送ります。
- 生成時の音声付きタグが **0個なら未設定エラー、2個以上なら競合エラー**。同じ音声ファイルでもタグが2つなら拒否します。継承された「自動的に持つタグ」も対象です。
- 返信には生成時の音声設定を保存します。後からタグ・画像・音声を変更しても過去の返信は別の声になりません。変更を適用するには返信を再生成してください。旧バージョンの履歴も再生成が必要です。
- 過去の返信を保護するため、音声の解除・差し替えで保存済みサンプルは削除しません。JSONバックアップには紐付けのみ含み、音声本体は含みません。別端末への復元では音声を選び直し、返信を再生成してください。
- 画面を離れると通信・再生を停止します。生成中のアイコンでも通信を中止できますが、PCの処理は完了／制限時間まで続く場合があります。TTSは永続キューではなく、失敗時は手動で再試行します。
- PCの音声一時ファイルは要求終了時に削除し、Androidの生成音声は再生終了／停止時に削除します。異常終了時の一時ファイルは残る場合があります。
- Tailscale等の信頼できるネットワーク内だけで利用し、`api_key` を設定してください。ポートをインターネットへ直接公開しないでください。

### APIと確認

`POST /api/v1/tts`（既存のBearer認証）:

```json
{
  "text": "こんにちは。",
  "voices": [{"audio_base64": "音声のBase64", "ref_text": "サンプルの文字起こし（省略可）"}]
}
```

成功は `audio/wav`、エラーは `{"error":"理由"}`。400: 入力不正、401: 認証、409: GPU使用中、503: モデル未設定、500: 生成失敗。サーバーも `voices` が1個であることを検証します。`GET /api/v1/health` の `tts_configured` はパスの設定有無であり、モデルの動作保証ではありません。

```bat
python -m unittest discover -s tests -v
```

テストはGPU不要です。実機では「単一タグで再生」「2タグで拒否」「継承タグの競合」「生成後の画像変更」「画面終了時の停止」「Forge生成中の拒否」を確認してください。
