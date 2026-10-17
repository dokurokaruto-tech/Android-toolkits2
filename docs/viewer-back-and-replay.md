# 全画面閲覧の戻る安定化・カードセット適用・再生成のシード/バッチ

## 戻る動作のクラッシュ修正

- 閲覧画面(FullScreenImageActivity)は onCreate で Glide の RequestManager を
  確保し、以降の target 解放はそれで行う。destroy 済み Activity からの
  Glide 取得は例外を投げ、戻る／回転時にサムネイル target を保持していると
  クラッシュしていた。
- 閉じるとき chrome 自動非表示タイマーも止め、サムネイル target も解放する。
- AlbumDetailActivity は onDestroy で wallpaper_prefs リスナーを解除するはずが
  登録していた（誤記）。解除に修正。

## カードセットを現在のセットに適用

- 閲覧画面下部の「このカード構成を現在のセットに適用」ボタン。
- 画像に使われたカードを解決し（保存済み構成→プロンプト→タグの順）、
  ビルダーの今のセットへの上書きを予約する。MainActivity が resume した
  ときに消費し、選択カードを差し替えてビルダー画面を見せる。
- ランダマイザーの当たりカードは固定選択になり、ランダマイザー分類は
  オフになる。次回生成がこの画像と同じ構成になる。
- undo 履歴に記録されるので、ビルダーの undo で戻せる。

## 再生成のシード変更とバッチカウント

- 再生成ダイアログに「シードを変える」スイッチを追加。ON ではシードを
  送らず、通常生成と同じく PC 側が 1 枚ごとにランダムで選ぶ。
- ON のときだけバッチカウントをその場で入力できる。初期値はビルダーの
  現在のバッチカウント。丸め規則もビルダーと同じ。
- 入力した枚数ぶんを一度に送る。生成中は「次に割り込み生成」になり、
  その枚数ぶんだけが割り込む。
- シードを変えない場合は従来どおり同じシードで必ず 1 枚。
- シードが残っていない画像はスイッチ ON 固定で開く。

## 検証

- `tools/check_replay_and_apply.sh`：純 Kotlin ロジック（KOTLINC/JAVA が必要）。
- `python3 -m unittest tools/test_viewer_back_and_apply.py`：配線のソース検査 14 件。
- `./gradlew :app:testDebugUnitTest`：GeneratedImageReplayPolicyTest、
  BuilderCardSetApplyPolicyTest、BuilderCardSetHandoffTest。
