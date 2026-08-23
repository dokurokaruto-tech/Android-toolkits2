package com.example.kennys_dokidoki_wallpaper

/**
 * 完成イラストの一覧から消すときの確認文。削除ボタンは右側（Positive）に置く。
 */
object GeneratedImageDeletePolicy {
    const val TITLE = "本当に削除しますか？"
    const val CANCEL = "キャンセル"
    const val DELETE = "削除する"

    fun title(count: Int): String =
        if (count <= 1) TITLE else "本当に ${count} 件を削除しますか？"

    fun message(count: Int, remote: Boolean): String {
        val target = if (count <= 1) "このイラスト" else "${count}件のイラスト"
        return if (remote) {
            "${target}をPCからも削除します。紐づいた仮チャットも消えます。"
        } else {
            "${target}を削除します。紐づいた仮チャットも消えます。"
        }
    }
}
