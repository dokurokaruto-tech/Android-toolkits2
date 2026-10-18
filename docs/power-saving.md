# 省電力思想（開いていない間は寝る）

アプリの画面を開いていない間、プロセスは画像を抱えず、周期タマーも
監視ポーリングも回さない。壁紙エンジンも見えていない間は静止する。
残すのは壁紙の表示と、ユーザーが明示的に始めた仕事（画像生成・
ローカルLLM生成のフォアグラウンドサービス）だけ。

```
Activity 起動中 1+
   │  FOREGROUND        Activity 0
   ├─ ImageMemoryGovernor の心拍 (10s)   → 停止
   ├─ 生成フォルダの live 監視 (8s/2s)   → 停止
   ├─ 原寸 ByteArray キャッシュ          → 全開放
   ├─ Glide memory cache                → clearMemory
   └─ 閲覧プレフェッチダウンロード       → 全キャンセル

壁紙 visible
   │  表示中            visible でない
   ├─ 描画・クロスフェード              → draw() は即帰還
   ├─ 設定変更で再デコード             → needsReload だけ立てて遅延
   └─ currentBitmap 保有                → null（再表示時に読み直す)
```

## 部品

- `AppPowerModePolicy`: 起動中 Activity 数で FOREGROUND/SLEEPING を判定
  する純粋ロジック（テスト対象）。
- `AppPowerGovernor`: `ActivityLifecycleCallbacks` で数を数え、遷移の
  瞬間だけ ImageMemoryGovernor / GeneratedLibraryLiveUpdate に寝起きを
  通達する。遷移がない時は一切仕事をしない。
- `ImageMemoryGovernor.onAppSleeping/onAppWaking`: 心拍の停止/再開と
  RAM 画像の全開放。通常のヒープ圧迫時の `clear()` は転送を止めず、
  就寝だけが `cancelTransfers()` で投機ダウンロードも止める。
- `GeneratedLibraryLiveUpdate`: 購読は残したまま背面ではポーリング
  job をキャンセル。前面復帰で再開。
- `MyWallpaperService.Engine`: `onVisibilityChanged(false)` で
  animator・タップ/テキストのハンドラ・両ビットマップを捨てる。
  非表示中の prefs 変更やブロードキャスト、完了した非同期ロードも
  描かず破棄し、`needsReload` として再表示順まで遅延。

## なぜ RAM を捨てると電池に効くか

DRAM 自体の消費は小さいが、RAM 上に画像を抱え続ける大規模プロセスは
Android に殺されにくく、本来停止できるプロセス全体（スレッド・
Looper の起床）が生きたままになる。真の浪費源は常時起きている
タマーとポーリングで、今回それらを「開いている時だけ」に封じた。

## 検証

- `tools/check_junit_policies.sh`（AppPowerModePolicyTest 含む）
- `python3 -m unittest tools/test_power_saving.py`：配線と静止処理
- `./gradlew :app:testDebugUnitTest`
