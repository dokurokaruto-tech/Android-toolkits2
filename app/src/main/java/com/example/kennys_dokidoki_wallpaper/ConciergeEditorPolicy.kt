package com.example.kennys_dokidoki_wallpaper

/** 保存済みデータではなく、開いている編集欄にだけ適用する。 */
internal object ConciergeEditorPolicy {
    enum class Kind { CARD, TAG }

    data class Draft(
        val key: String,
        val kind: Kind,
        val label: String,
        val main: String,
        val negative: String = ""
    )

    fun canApply(before: Draft, current: Draft?): Boolean = before == current

    fun context(draft: Draft): String = buildString {
        appendLine("編集対象は次の1件に固定。他のカード・タグを探したり変更したりしない。")
        appendLine("種類: ${draft.kind} / ID: ${draft.key} / 名前: ${draft.label}")
        appendLine("現在の未保存の本文:\n${draft.main}")
        if (draft.kind == Kind.CARD) {
            appendLine("現在の未保存のネガティブ:\n${draft.negative}")
        }
    }
}
