# 日記の Google Drive 連携を有効にする手順

アプリ側の実装は入っているが、Google 側でOAuthクライアントを登録しないと
サインインが `ApiException: 10 (DEVELOPER_ERROR)` で失敗する。ビルドする人が
一度だけ次の設定を行うこと。

## 1. 署名の SHA-1 を取る

リポジトリ直下の `debug.keystore` で署名しているので、その指紋を使う。

```
keytool -list -v -keystore debug.keystore -alias androiddebugkey \
  -storepass android -keypass android
```

## 2. Google Cloud Console で登録する

1. プロジェクトを作る（既存でもよい）
2. 「APIとサービス」→ ライブラリ → **Google Drive API** を有効化
3. 「OAuth同意画面」を作成し、スコープに
   `https://www.googleapis.com/auth/drive.file` を追加
   （公開前はテストユーザーに自分のアカウントを登録する）
4. 「認証情報」→ OAuthクライアントID → **Android**
   - パッケージ名: `com.example.kennys_dokidoki_wallpaper`
   - SHA-1: 手順1の値

クライアントIDをアプリに埋め込む必要はない。Android クライアントは
パッケージ名と署名で照合される。

## 3. 動作

- 日記のカレンダー右上のドライブアイコン → 「Googleアカウントと連携」
- Drive に `Kennys日記` フォルダが作られ、`YYYY-MM-DD.json` と
  `img_<uuid>.img` が置かれる
- 日記を保存するたびに自動アップロード。カレンダーを開くたびに
  Drive 側が新しければ取り込む（更新時刻で比較、5秒以内の差は無視）
- 権限は `drive.file`。このアプリが作ったファイル以外は読めない
