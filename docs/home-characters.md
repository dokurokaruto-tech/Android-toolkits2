# ホームのキャラクター

カルーセル上の「キャラ変更」から後藤ひとり／喜多郁代／伊地知虹夏／山田リョウを選択する。
初期値は既存の後藤ひとり。選択は再起動・画面再生成後も保持する。

## 描画

既存の `BocchiLive2dStageView` と `BocchiPhysicsEngine` を共用する。
**Live2D Cubism SDK／moc3モデルではない。** Canvas の画像メッシュ変形による
独自2Dアニメーション。SDK、ネット接続、追加モデルのダウンロードは不要。

- 呼吸、首・髪・スカートの揺れ、自動瞬き、演奏動作。
- スクロールで視線・体・ギターが反応。
- 表情は9種。自動瞬き（half_blink/blink）、セクション別の待機顔、
  頭タップでウインク（陽キャ）かパニック（陰キャ）、
  背景タップで驚き、演奏タップでGroove。
- 喜多は短い髪向けに揺れを抑制。キューブ髪飾り・アホ毛・グリッチは無効。
- 虹夏は三角のアホ毛を保持し、サイドテールの揺れを強調。
- リョウはボブなので揺れを大きく抑制。 Deadpan のパニック顔を使う。
- ステージのアクセント色・キットの色はキャラごとに差し替え。
- 切替時は物理状態を初期化し、選択中の機能セクションは維持。
- 全素材の読み込み成功後に交換。失敗時は現在のキャラクターを維持。

### ドラム

虹夏だけ `Instrument.DRUMS`。ギター系の弦デフォーマは適用されない。
ドラムキットはコードで描画し、150BPM の8分音符グリッド
（`BocchiLive2dPolicy.drumPattern`）でヒットを再生する。

- バスドラムは足。シェル・スネア・タム・フロアタム・ハイハット・クラッシュを描画。
- ヒットでヘッドが膨らみ、衝撃輪が広がる。
- スティックは手元UV（`HomeCharacter.handLeft/handRight`）からヘッドへ伸びる。
- 両手は8分音符ごとに符号が反転し、交互に上がり下がりする。

## 素材・参考

公式紹介（2026-10-01参照）:
https://www.aniplex.co.jp/lineup/bocchi/news/detail/?id=60203

「明るく人と関わることが好き」「ギターボーカル担当」を表情・反応の参考にした。
今回の画像はAI生成の非公式ファンアート。公式画像や第三者のLive2Dモデルを
アプリ素材として転載していない。作品・キャラクターの権利は各権利者に帰属する。
生成した素材の同梱は、権利者からの配布許諾を意味しない。

キャラクターごとのデザイン根拠:

| キャラ | 楽器 | 衣装のポイント |
| --- | --- | --- |
| 後藤ひとり | レスポール・カスタム | 既存のピンクジャージ＋ネオンサイバー |
| 喜多郁代 | 黒ギブソンSG系 | 黒ボレロ＋白フリル、赤タータンのミニスカート、赤タイツ |
| 伊地知虹夏 | ドラム（TAMA Imperialstar 系） | 黒の袖なしタートルネック＋黄ストライプ、黄黒スカート |
| 山田リョウ | 白のプレシジョンベース | 黒のゆるいセーター、ダークグレーのミニスカート |

### リグ規約

全キャラが同じ 592×750 のキャンバスを共有する。顔パッチは
`assets/live2d/<dir>/<dir>_face_<key>.webp` で、156×126 の顔ボックス
(160, 90, 316, 216) に `drawBitmapMesh` で引き伸ばされる。
表情キーは `BocchiExpression.key`。

`tools/gen/<name>/body.png` と `<key>.png` が元画像。パッチは外輪をボディと
同一ピクセルに保ち、眉・目・口のバンドだけを差し替えるので長方形の継ぎ目が出ない。
バンドは `build_faces.py` がマスク済み正規化相互相関で位置合わせする。

```sh
python3 tools/build_faces.py kita half_blink blink smile awakened panic laugh wink surprise
python3 tools/build_faces.py bocchi --recycle half_blink blink smile awakened panic --
python3 -m unittest discover -s tools -p 'test_live2d_assets.py'
```

## 検証

- 素材テスト2件: 透過、寸法、緑背景除去、足元フェード、
  顔パッチの境界・差分存在・シルエット外はみ出しを確認。
- Kotlin 2.2.10による実ソースの独立モーション検証: 3,600フレーム成功。
  保存値復元、既存キャラ互換、頭タップ、瞬き、髪揺れ、頂点の有限値、顔位置を確認。
  Android生成リソースIDのみスタブ。描画・Activityは対象外。
- `BocchiLive2dStageView` は `tools/graphics_stubs` で型検査のみ実施。

```sh
JAVA_HOME=/path/to/java KOTLINC=/path/to/kotlinc tools/check_character_motion.sh
JAVA_HOME=/path/to/java KOTLINC=/path/to/kotlinc tools/check_home_character.sh
JAVA_HOME=/path/to/java KOTLINC=/path/to/kotlinc tools/check_stage_view.sh
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

`HomeCharacterTest` にJUnitテスト8件、`BocchiLive2dPolicyTest` は踏襲。
虹夏・リョウの素材は未生成のため `assetsBelongToCharacter` が失敗する。
Gradle配布先とのTLS接続が失敗し、Android SDKも取得できないため、
Gradleテスト・APKビルド・実機描画は未検証。

実機で確認する項目:
1. 4キャラの切替、再起動・回転後の選択保持。
2. 待機時の瞬き・呼吸、頭／ギターのタップ、背景タップ、カルーセルスクロール。
3. 虹夏のドラムヒットとスティックの動き。
4. 機能画面から戻った際のアニメーション再開、バックグラウンド時の停止。
5. 小画面・横画面・大きな文字設定でのボタンとカルーセルの操作。
