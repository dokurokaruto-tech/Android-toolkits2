package com.example.kennys_dokidoki_wallpaper

/**
 * 閲覧画面で確定したカード構成をビルダーの今のセットへ上書きする依頼の受け渡し。
 * Binderへ載せずプロセス内で持つ。MainActivity が resume したときに消費する。
 * 予約は最後の1件だけ残す。プロセス終了と一緒に消えるのは許容する。
 */
internal object BuilderCardSetHandoff {
    /** 上書きするカード構成と、閲覧画面で選んだ復元方式。 */
    data class Request(
        val source: GeneratedImagePresetPolicy.Source,
        val mode: GeneratedImagePresetPolicy.FromImageMode
    )

    private var pending: Request? = null

    fun offer(request: Request) {
        pending = request
    }

    fun consume(): Request? {
        val current = pending
        pending = null
        return current
    }
}
