# three.js 壁紙（組み込み作品）

画像ともう一つ、壁紙として three.js 作品を表示できる。作品はユーザーが
追加するのではなく、アプリに最初から組み込まれた要素として同梱する。
管理は従来のイメージセットの仕組みをそのまま踏襲する。

```
イメージセット編集
  └─ [three.js 作品を表示] トグル（専用ボタン）
       ├─ オン  → タグ選択・一致モードをロック / 画像0枚警告スキップ
       └─ オフ → 従来のタグ検索セット

壁紙プール
  ├─ 画像セット   → タグ一致した ImageEntry（従来）
  └─ three.js セット → ThreeJsWork 目録（32 / 36 / 38 の3作品）
```

## 描画パイプライン

three.js は WebView で動かすが、タッチは WebView に渡さない。オフスクリーン
仮想ディスプレイで描画し、フレームを Bitmap として既存の壁紙描画に合流させる。
ジェスチャーは従来どおり Engine の onTouchEvent が処理する。

```
WebView(three.js player)
   │  requestAnimationFrame
   ▼
VirtualDisplay (160dpi, 1dp=1px)
   │  SurfaceFlinger 合成
   ▼
ImageReader (RGBA_8888, GPU usage, 3バッファ)
   │  acquireLatestImage → HardwareBuffer
   ▼
Bitmap.wrapHardwareBuffer ─> Listener.onFrame ─> Engine.draw()
                                                  ├─ クロスフェード
                                                  ├─ セット名テキスト
                                                  └─ ツールファインダー
```

- `ThreeJsStageRenderer`: 仮想ディスプレイ / Presentation / ImageReader /
  WebView をまとめる高レベル API。`start / loadWork / snapshot / release`。
  直近2フレームの Image を保持して GPU 読み取り中のバッファ再利用を防ぐ。
- `snapshot()`: 現在フレームをソフトウェア Bitmap に複製（GPU リードバック）。
  クロスフェードの残像用。ImageReader のバッファ寿命から切り離す。
- 非表示（`onVisibilityChanged(false)` → `goQuiet`）で `release()`。
  仮想ディスプレイと WebView を丸ごと解放する（省電力思想）。再表示で再生成。

## ジェスチャー

three.js 表示中もホームのジェスチャーはすべて有効。

- 次の壁紙（既定: ダブルタップ）→ `ThreeJsSetPolicy.nextIndex` で
  **現在と異なる作品をランダム**に選び、クロスフェードで切替。
- 次のセット / 特定セット → `switchToSet` が three.js ⇔ 画像の双方を処理。
  three.js → 画像は直前フレームを snapshot して残像にし renderer を解放。
- 作品インデックスは画像と同じ `last_index_for_album_<セット名>` に保存。

## 組み込み目録とプレイヤー

- `ThreeJsWork`: 作品の唯一の供給源（enum）。id / タイトル / asset パス /
  ポスター URI を持つ。壁紙・セット一覧・アルバム詳細はすべてこれを参照。
- `assets/threejs/three.min.js`: **r116 固定**。作品スクリプトが使う旧
  CubeCamera API（32）と InstancedMesh（36）が共存する唯一の互換帯。
- `assets/threejs/player.js`: three.js Editor の APP.Player と同じ手順で
  JSON（scene / camera / scripts / project）を読み込み、`this=object` で
  スクリプトを実行し update ループを回す。旧表記の envMap（レンダーターゲット
  直指定）は読み込み後に texture へ正規化。
- `assets/threejs/works/posters/*.webp`: 各作品の静止ポスター。読み込み中の
  黒画面防止と、セット一覧 / アルバム詳細のサムネイルに使う。

## 管理 UI

- セット一覧（`ImageSetAdapter`): three.js セットはポスターをサムネイルに、
  件数を「three.js 作品 3件 [組み込み]」で表示。壁紙適用ボタンも有効。
- アルバム詳細（`AlbumDetailActivity`): 画像グリッドの代わりに作品カードを
  表示。タップでその作品を壁紙に適用（インデックス保存＋変更通知）。
- データ（`DataManager` / `BackupManager`): `threeJsEnabled` を永続化・
  バックアップ復元。three.js セットの `filterImages` は常に空。

## 検証

```sh
python3 -m unittest tools/test_threejs_wallpaper      # 配線・アセット契約
JAVA_HOME=... KOTLINC=... tools/check_threejs_policy.sh  # 純粋ロジックJUnit
```

- 3作品（32/36/38）はヘッドレス Chrome（SwiftShader）で実描画・アニメーション・
  コンソールエラー0を確認済み。ポスターも同環境で生成。
- Kotlin 2.2.10 でモデル層（ImageSet / ImageEntry / ThreeJsWork / Policy）の
  コンパイルとポリシーテスト7件の合格を確認。
- Android SDK / 実機が必要なレンダラー・Engine・UI の実描画は未確認
  （この環境では SDK を取得できないため）。
