package com.example.kennys_dokidoki_wallpaper

/**
 * キャラチャットへ渡している指示を、一塊にせず要素ごとに分ける。
 */
object ChatInstructionPolicy {
    const val ROLE_TEXT = "あなたはAIキャラクターです。"

    val SUGGEST_BODY = """
        ユーザーが次に返信しやすくなるような、ユーザーの返信のサジェスト（選択肢）を3パターン生成する。
        会話の口調と態度を反映し、末尾に次の形式で付ける。
        <<<SUGGESTIONS>>>
        A: <短い返信、15文字以内>
        B: <違うニュアンス、15文字以内>
        C: <少し甘えるかからかう返信、15文字以内>
        <<</SUGGESTIONS>>>
    """.trimIndent()

    enum class Kind { ROLE, USER, IMAGE, TAG, MEMORY, SUGGEST, SUGGEST_EXTRA }

    data class Section(
        val kind: Kind,
        val title: String,
        val subtitle: String,
        val body: String,
        val empty: Boolean = false
    )

    data class Snapshot(
        val personaName: String? = null,
        val personaItems: List<String> = emptyList(),
        val imageDescription: String? = null,
        val tags: List<Pair<String, String>> = emptyList(),
        val memories: List<String> = emptyList(),
        val suggestEnabled: Boolean = false,
        val suggestExtra: String = ""
    )

    fun sections(snapshot: Snapshot): List<Section> {
        val out = mutableListOf<Section>()
        out += Section(
            Kind.ROLE,
            ChatInstructionCopy.ROLE,
            ChatInstructionCopy.ROLE_SUB,
            ROLE_TEXT
        )
        val persona = snapshot.personaName?.trim().orEmpty()
        val items = snapshot.personaItems.map { it.trim() }.filter { it.isNotEmpty() }
        if (items.isEmpty()) {
            out += Section(
                Kind.USER,
                ChatInstructionCopy.USER,
                persona.ifEmpty { ChatInstructionCopy.EMPTY },
                "",
                empty = true
            )
        } else {
            items.forEachIndexed { index, content ->
                out += Section(
                    Kind.USER,
                    if (persona.isEmpty()) ChatInstructionCopy.USER else "${ChatInstructionCopy.USER} · $persona",
                    ChatInstructionCopy.part(index + 1, items.size),
                    content
                )
            }
        }
        val image = snapshot.imageDescription?.trim().orEmpty()
        out += Section(
            Kind.IMAGE,
            ChatInstructionCopy.IMAGE,
            if (image.isEmpty()) ChatInstructionCopy.EMPTY else ChatInstructionCopy.IMAGE_SUB,
            image,
            empty = image.isEmpty()
        )
        if (snapshot.tags.isEmpty()) {
            out += Section(Kind.TAG, ChatInstructionCopy.TAG, ChatInstructionCopy.EMPTY, "", empty = true)
        } else {
            snapshot.tags.forEach { (name, prompt) ->
                val body = prompt.trim()
                out += Section(
                    Kind.TAG,
                    name.trim().ifEmpty { ChatInstructionCopy.TAG },
                    ChatInstructionCopy.TAG,
                    body,
                    empty = body.isEmpty()
                )
            }
        }
        val memories = snapshot.memories.map { it.trim() }.filter { it.isNotEmpty() }
        if (memories.isEmpty()) {
            out += Section(Kind.MEMORY, ChatInstructionCopy.MEMORY, ChatInstructionCopy.EMPTY, "", empty = true)
        } else {
            memories.forEachIndexed { index, memory ->
                out += Section(
                    Kind.MEMORY,
                    ChatInstructionCopy.MEMORY,
                    ChatInstructionCopy.part(index + 1, memories.size),
                    memory
                )
            }
        }
        if (snapshot.suggestEnabled) {
            out += Section(Kind.SUGGEST, ChatInstructionCopy.SUGGEST, ChatInstructionCopy.SUGGEST_ON, SUGGEST_BODY)
            val extra = snapshot.suggestExtra.trim()
            out += Section(
                Kind.SUGGEST_EXTRA,
                ChatInstructionCopy.SUGGEST_EXTRA,
                if (extra.isEmpty()) ChatInstructionCopy.EMPTY else ChatInstructionCopy.SUGGEST_EXTRA_SUB,
                extra,
                empty = extra.isEmpty()
            )
        } else {
            out += Section(
                Kind.SUGGEST,
                ChatInstructionCopy.SUGGEST,
                ChatInstructionCopy.SUGGEST_OFF,
                "",
                empty = true
            )
        }
        return out
    }

    fun preview(section: Section): String {
        if (section.empty) return ChatInstructionCopy.EMPTY
        return section.body.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
            .ifEmpty { ChatInstructionCopy.EMPTY }
    }

    fun countLine(sections: List<Section>): String {
        val filled = sections.count { !it.empty }
        return ChatInstructionCopy.count(filled, sections.size)
    }
}

object ChatInstructionCopy {
    const val TITLE = "指示書"
    const val ROLE = "役割"
    const val ROLE_SUB = "AIキャラチャット"
    const val USER = "ユーザー"
    const val IMAGE = "この絵"
    const val IMAGE_SUB = "個別の設定"
    const val TAG = "タグ"
    const val MEMORY = "記憶"
    const val SUGGEST = "返信の候補"
    const val SUGGEST_ON = "送るときに付ける"
    const val SUGGEST_OFF = "いまは付けない"
    const val SUGGEST_EXTRA = "候補の補足"
    const val SUGGEST_EXTRA_SUB = "今回の追加ルール"
    const val EMPTY = "未設定"
    const val CLOSE = "閉じる"
    const val BACK = "戻る"

    fun part(index: Int, total: Int): String = "$index / $total"

    fun count(filled: Int, total: Int): String = "使っている $filled / $total"
}
