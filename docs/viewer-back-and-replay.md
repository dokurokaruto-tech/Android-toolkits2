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
- ランダマイザーを使った画像では、適用時に復元方法を2つから選ぶ。
  「使われたカードをそのまま復元」では当たりカードも固定選択にし、
  ランダマイザー分類はオフになる（次回生成がこの画像と同じ構成になる）。
  「ランダマイザーのon/offも再現」では分類をオンのまま残し、当たった
  カードだけ選択を外して次回以降も抽選に任せ直す。
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

## 個別ランダマイザーは選択したカードだけ

以前は、個別ランダマイザーが有効なカードは選択していなくても確率で
プロンプトに混ざっていた。今は次を満たしたときだけに入る。

```
選択中？ ──いいえ──→ プロンプトに入れない（抽選もしない）
  │
 はい
  │
個別ランダマイザー有効？ ──いいえ──→ 毎回入る
  │
 はい
  │
1枚ごとに抽選 ──当たり──→ 入る（選択レベルで）
               └外れ──→ その1枚だけ入らない
```

- 外れたカードは分類抽選の候補にも戻さない（従来どおり）。
- 画像からの復元では個別ランダマイザーのカードを「当たり」に数えない。
  選択を解除すると二度と出なくなるため、どちらの復元方法でも
  選択状態を保つ。

## 検証

- `tools/check_replay_and_apply.sh`：純 Kotlin ロジック（KOTLINC/JAVA が必要）。
- `tools/check_junit_policies.sh`：GeneratedImageTagBindingTest、GeneratedImagePresetPolicyTest、
  BuilderCardSetApplyPolicyTest、BuilderCardSetHandoffTestをスタブ実行（KOTLINC/JAVA が必要）。
- `python3 -m unittest tools/test_viewer_back_and_apply.py`：配線のソース検査 20 件。
- `./gradlew :app:testDebugUnitTest`：GeneratedImageReplayPolicyTest、
  GeneratedImageTagBindingTest、GeneratedImagePresetPolicyTest、
  BuilderCardSetApplyPolicyTest、BuilderCardSetHandoffTest。
