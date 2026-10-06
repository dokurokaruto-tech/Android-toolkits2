# character3d — 後藤ひとり (Gotoh Hitori) セルルック 3D モデル

『ぼっち・ざ・ろっく！』後藤ひとりを、日本のアニメーション制作現場の
 NPR（ノン・フォトリアリスティック・レンダリング）作法に倣った
**セルルック（cel-look）の 3D キャラクターモデル**として構築したプロジェクトです。
Blender 4.5 LTS を **MCP (Model Context Protocol) 経由で操って**制作しました。

* 身長 156 cm（公式プロフィール準拠）、単位はメートル、Z-up、キャラクターは -Y 向き
* A ポーズ（肩 45°）— 今後のリギング／スキニングを見据えたNeutral スタンド pose
* **この段階ではスキニング／リギングは行っていません**（指示どおり）
* 成果物: `gotoh_hitori.blend` / `renders/final_*.png`

---

## 1. 環境（このサンドボックスでの再現手順）

サンドボックスには GPU も X サーバも apt リポジトリもないため、
以下の構成で Blender と MCP を導入しました。

| 要素 | 手段 |
|---|---|
| Blender 4.5.14 LTS | PyPI の `bpy` モジュール（GUI 不要）を `~/blender_env` venv に導入 |
| 欠損 X/GL 共有ライブラリ | `~/stublibs` に no-op スタブ `.so` を自前ビルド（`LD_LIBRARY_PATH` で解決。ヘッドレスでは通らないコードパス専用シンボルのみ） |
| MCP サーバ | PyPI `mcp-for-blender` 2.1.8（旧 blender-mcp） |
| Blender 側ブリッジ | `host/blender_mcp_host.py` — 同梱 `addon.py` の `BlenderMCPServer` クラスを**そのまま**利用し、GUI が必要な `bpy.app.timers` 排水ループだけをメインスレッド自前ポンプに置換。ポート **9876** で公式プロトコルと完全互換 |
| MCP クライアント | `tools/mcp_run.py`（stdio で MCP サーバを起動し `execute_blender_code` 等を呼ぶ） |

 telemetry は環境変数 `BLENDER_MCP_DISABLE_TELEMETRY=1` で無効化済み。

### 起動手順

```bash
# 1) Blender ホスト（MCP ブリッジ）を起動
LD_LIBRARY_PATH=$HOME/stublibs BLENDER_MCP_DISABLE_TELEMETRY=1 \
  HH_SAVE_PATH=$PWD/gotoh_hitori.blend \
  $HOME/blender_env/bin/python host/blender_mcp_host.py &

# 2) MCP 経由で Blender を操作
BLENDER_MCP_DISABLE_TELEMETRY=1 $HOME/blender_env/bin/python tools/mcp_run.py scene
BLENDER_MCP_DISABLE_TELEMETRY=1 $HOME/blender_env/bin/python tools/mcp_run.py exec build/step_00_scene.py
...
BLENDER_MCP_DISABLE_TELEMETRY=1 $HOME/blender_env/bin/python tools/mcp_run.py exec build/render_views.py
```

## 2. ビルドパイプライン（全て MCP 経由で実行）

| script | 内容 |
|---|---|
| `build/hh_lib.py` | 手続モデリングkit（ロフト／チューブ／ベジェ／リボン／join 等、バックグラウンド-safe） |
| `build/step_00_scene.py` | シーン・コレクション・**セルルックマテリアルキット**・カメラ5台・Freestyle 設定 |
| `build/step_01_body.py` | 素体（頭・頸・胴・腕・手・脚・足）1696 quads + subsurf |
| `build/step_02_face.py` | 目（強膜/虹彩/瞳/ハイライト）・まつげ・眉・口 |
| `build/step_03_hair.py` | スカルプ・前髪9束・サイドロック・バックヘア（尖った房付き）・アホ毛・キューブピン |
| `build/step_04_outfit.py` | ジャージ（襟/ジッパー/ piping /袖）・プリーツスカート・ソックス・ローファー・ギターケース |
| `build/step_05_polish.py` | ラインウェイト・メタデータ・統計・`.blend` 保存 |
| `build/render_views.py` | 最終レンダリング 5 views |

## 3. セルルックの技術メモ（業界作法）

* **フラット emission セルシェーダ**: `dot(N, key_dir)` を constant ランプで2トーン化。
  ライトトランスポートを使わないためノイズゼロ＝“塗り”たセルそのもの。
* **顔の投影法線（projected normal）**: 顔マテリアルのみ法線を頭中心の球へ投影し、
  鼻や眉のジオメトリに引っ張られない**1本の綺麗な影線**を実現（アニメ顔の定番）。
* **偽オクルージョンバンド**: 前髪の下・顎下の影をオブジェクト空間 Z バンドで付与。
* **天使の輪（angel ring）**: 髪にオブジェクト空間 Z のグラデーションバンドでハイライト帯。
* **線画は Freestyle**: silhouette + border + crease を 1.9 px のインク線として合成
  （Cycles では inverse-hull 法が使えないための正攻法）。
* 虹彩は縦グラデ（上が濃紺・下が水色）+ 瞳孔 + ハイライト球のレイヤ構成。

## 4. 注意事項

* リポジトリ原有の「リファレンス画像」
  (`app/src/main/res/drawable/img_*.jpg`) は **バイナリが UTF-8 置換文字化で破損**
  しており復元不可能でした（git 履歴も1コミットのみ）。
  そのため `refs/REFERENCES.md` に記載の Web 資料（公式フィギュア写真等）を
  リファレンスに使用しています。
* 本モデルは研究・学習目的のファンメイドです。著作権は各権利者に帰属します。
