package com.example.kennys_dokidoki_wallpaper

/**
 * 送信ボタンの「送信 ↔ 停止」切り替えと、ユーザーが返信を中断したときの
 * バブル表示のルール。
 */
object ChatInterruptPolicy {
    enum class SendAction { SEND, STOP }

    /** 出力がまだない状態で中断したとき、空で残さないための明示マーカー。 */
    const val INTERRUPTED_TEXT = "（返信を中断しました）"

    private val pendingPrefixes = listOf(
        "思考中", "推論中", "🧠 推論中", "📥 モデルをロードしています"
    )

    /** 進行中の仮アニメーション文なら true。部分出力の本文は false にして保持する。 */
    fun isPendingPlaceholder(text: String): Boolean =
        text.isBlank() || pendingPrefixes.any { text.startsWith(it) }

    /** 中断で確定するバブル本文。部分出力があれば保持し、なければ中断マーカーにする。 */
    fun interruptedBubbleText(text: String): String =
        if (isPendingPlaceholder(text)) INTERRUPTED_TEXT else text

    /** この画面のセッションで生成中なら、ボタンは停止操作になる。 */
    fun actionFor(isGenerating: Boolean, activeSessionId: String?, currentSessionId: String?): SendAction =
        if (isGenerating && activeSessionId != null && activeSessionId == currentSessionId) {
            SendAction.STOP
        } else {
            SendAction.SEND
        }
}
