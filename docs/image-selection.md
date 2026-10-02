# サムネイルの選択保持

`ImageAdapter`（PC生成画像・アルバム）と `AllImagesAdapter`（全画像）で
`ImageSelection` を共用する。

- 保存するのは画像キー。表示位置やImageEntryインスタンスには結び付けない。
- PC画像は日付＋ファイル名。接続先・認証tokenの変更では選択を外さない。
- 端末画像は完全なURI。クエリが異なるURIを勝手に同一視しない。
- 追加・並べ替え・同一画像のメタデータ更新では選択を保持。
- 削除・フィルターによって表示対象から消えた画像だけ選択解除。
- 全選択は実行時のスナップショット。後から追加された画像を自動選択しない。
- 範囲選択の起点も画像キー。起点が消えたら次の長押し画像だけを追加。
- 最後の選択が消えたら件数を0にし、選択モードを終了。
- タップ・長押し・メニュー操作は表示した画像キーから現在の対象を解決。
  その画像が消えていたら操作しない。
- バッチ操作には現在のリスト内の同一画像だけを渡す。

リスト更新の契約は従来通り、共有リストの変更後にAdapterへnotifyする。
`AllImagesAdapter.updateList` でも同じ同期処理を行う。
再描画ごとの選択確認はキー集合で行い、ファイルI/Oはしない。

## 検証

```sh
JAVA_HOME=/path/to/java KOTLINC=/path/to/kotlinc tools/check_image_selection.sh
./gradlew :app:testDebugUnitTest :app:connectedDebugAndroidTest
```

- 旧インデックス方式で「先頭に1枚追加すると選択がずれる」を再現。
- Kotlin 2.2.10の独立回帰検証: 成功。
- 実Adapterソース＋Android/RecyclerViewスタブによる検証:
  旧コードで失敗、修正コードで成功。挿入、古い表示のクリック／長押し、
  削除時の件数・モード、token更新後のImageEntry、全画像リスト差し替えを確認。
- JVMテスト7件、Android上のAdapterテスト3件を追加。
- GradleのTLS接続失敗により、Gradleテスト・実機描画・APKビルドは未確認。
