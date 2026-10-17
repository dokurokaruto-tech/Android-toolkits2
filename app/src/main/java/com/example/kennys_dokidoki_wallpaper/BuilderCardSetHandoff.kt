package com.example.kennys_dokidoki_wallpaper

/**
 * 閲覧画面で確定したカード構成をビルダーの今のセットへ上書きする依頼の受け渡し。
 * Binderへ載せずプロセス内で持つ。MainActivity が resume したときに消費する。
 * 予約は最後の1件だけ残す。プロセス終了と一緒に消えるのは許容する。
 */
internal object BuilderCardSetHandoff {
    private var pending: GeneratedImagePresetPolicy.Source? = null

    fun offer(source: GeneratedImagePresetPolicy.Source) {
        pending = source
    }

    fun consume(): GeneratedImagePresetPolicy.Source? {
        val current = pending
        pending = null
        return current
    }
}
