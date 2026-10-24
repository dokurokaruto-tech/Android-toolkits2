package com.example.kennys_dokidoki_wallpaper

/** Prompt/result handling for composing the user's first chat message without sending it. */
internal object ChatFirstUserLinePolicy {
    fun buildRequest(profileName: String, savedInstructions: String): String = buildString {
        append("キャラクターに送る、ユーザー側の最初の発言を1つだけ作成してください。")
        append("会話履歴にキャラクターの初回挨拶がある場合は、それに自然につながる内容にしてください。")
        append("ユーザー本人の発言として一人称・視点を保ち、キャラクターの返事は作らないでください。")
        append("発言本文だけを出力し、前置き、説明、候補、番号、引用符、役割ラベルは付けないでください。")
        if (savedInstructions.isNotBlank()) {
            append("\n\n現在のSuggestプロフィール「")
            append(profileName)
            append("」に保存された口調・例文の文章を、返信候補を作るときと同じように指示としてそのまま適用してください。")
            append("保存文自体を発言へ引用せず、口調や例文の方向性だけを反映してください。")
            append("\n【プロフィールに保存された文章】\n")
            append(savedInstructions)
            append("\n【保存文ここまで】")
        }
        append("\n\n【最終出力】ユーザーの発言本文を1つだけ出力し、返信候補・A/B/C形式・キャラクターの返答・解説は付けないでください。")
    }

    fun cleanGeneratedLine(raw: String?): String? {
        if (raw.isNullOrBlank()) {
            return null
        }
        var text = ChatSuggestionParser.visibleText(raw).trim()
        if (text.startsWith("```")) {
            text = text.removePrefix("```").removePrefix("text").removePrefix("\n")
                .removeSuffix("```").trim()
        }
        val wrappers = listOf("「" to "」", "『" to "』", "\"" to "\"", "“" to "”")
        val wrapper = wrappers.firstOrNull { (open, close) -> text.startsWith(open) && text.endsWith(close) && text.length >= open.length + close.length }
        if (wrapper != null) {
            text = text.substring(wrapper.first.length, text.length - wrapper.second.length).trim()
        }
        return text.takeIf { it.isNotBlank() }
    }
}
