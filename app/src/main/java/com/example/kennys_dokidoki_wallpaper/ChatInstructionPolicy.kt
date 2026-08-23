package com.example.kennys_dokidoki_wallpaper

/**
 * キャラチャットの指示を、分類→個別の二段にする。
 */
object ChatInstructionPolicy {
    const val ROLE_TEXT = "あなたはAIキャラクターです。"
    const val ROLE_KEY = "chat_role_instruction"
    const val SUGGEST_KEY = "chat_suggest_instruction"

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

    data class Target(val kind: Kind, val key: String)

    data class Leaf(
        val title: String,
        val subtitle: String,
        val body: String,
        val target: Target,
        val empty: Boolean = false
    )

    data class Category(
        val kind: Kind,
        val title: String,
        val subtitle: String,
        val leaves: List<Leaf>
    )

    sealed class Row {
        data class Group(val category: Category, val expanded: Boolean) : Row()
        data class Item(val leaf: Leaf) : Row()
    }

    data class Snapshot(
        val roleText: String = ROLE_TEXT,
        val personaName: String? = null,
        val personaItems: List<Pair<String, String>> = emptyList(),
        val imageDescription: String? = null,
        val tags: List<Pair<String, String>> = emptyList(),
        val memories: List<String> = emptyList(),
        val suggestEnabled: Boolean = false,
        val suggestBody: String = SUGGEST_BODY,
        val suggestExtra: String = ""
    )

    fun roleText(stored: String?): String = stored?.trim().orEmpty().ifEmpty { ROLE_TEXT }

    fun suggestText(stored: String?): String = stored?.trim().orEmpty().ifEmpty { SUGGEST_BODY }

    fun categories(snapshot: Snapshot): List<Category> {
        val role = roleText(snapshot.roleText)
        val persona = snapshot.personaName?.trim().orEmpty()
        val userLeaves = snapshot.personaItems
            .map { (id, content) -> id.trim() to content.trim() }
            .filter { it.first.isNotEmpty() && it.second.isNotEmpty() }
            .mapIndexed { index, (id, content) ->
                Leaf(
                    title = if (persona.isEmpty()) ChatInstructionCopy.USER else "${ChatInstructionCopy.USER} · $persona",
                    subtitle = ChatInstructionCopy.part(index + 1, snapshot.personaItems.count { it.second.trim().isNotEmpty() }),
                    body = content,
                    target = Target(Kind.USER, id)
                )
            }
            .ifEmpty {
                listOf(
                    Leaf(
                        ChatInstructionCopy.USER,
                        persona.ifEmpty { ChatInstructionCopy.EMPTY },
                        "",
                        Target(Kind.USER, ""),
                        empty = true
                    )
                )
            }
        val image = snapshot.imageDescription?.trim().orEmpty()
        val tagLeaves = if (snapshot.tags.isEmpty()) {
            listOf(Leaf(ChatInstructionCopy.TAG, ChatInstructionCopy.EMPTY, "", Target(Kind.TAG, ""), empty = true))
        } else {
            snapshot.tags.map { (name, prompt) ->
                val body = prompt.trim()
                Leaf(
                    title = name.trim().ifEmpty { ChatInstructionCopy.TAG },
                    subtitle = ChatInstructionCopy.TAG,
                    body = body,
                    target = Target(Kind.TAG, name.trim()),
                    empty = body.isEmpty()
                )
            }
        }
        val memories = snapshot.memories.map { it.trim() }
        val memoryLeaves = if (memories.none { it.isNotEmpty() }) {
            listOf(Leaf(ChatInstructionCopy.MEMORY, ChatInstructionCopy.EMPTY, "", Target(Kind.MEMORY, "0"), empty = true))
        } else {
            memories.mapIndexed { index, memory ->
                Leaf(
                    ChatInstructionCopy.MEMORY,
                    ChatInstructionCopy.part(index + 1, memories.size),
                    memory,
                    Target(Kind.MEMORY, index.toString()),
                    empty = memory.isEmpty()
                )
            }
        }
        val suggestLeaves = if (snapshot.suggestEnabled) {
            val extra = snapshot.suggestExtra.trim()
            listOf(
                Leaf(
                    ChatInstructionCopy.SUGGEST,
                    ChatInstructionCopy.SUGGEST_ON,
                    suggestText(snapshot.suggestBody),
                    Target(Kind.SUGGEST, "body")
                ),
                Leaf(
                    ChatInstructionCopy.SUGGEST_EXTRA,
                    if (extra.isEmpty()) ChatInstructionCopy.EMPTY else ChatInstructionCopy.SUGGEST_EXTRA_SUB,
                    extra,
                    Target(Kind.SUGGEST_EXTRA, "extra"),
                    empty = extra.isEmpty()
                )
            )
        } else {
            listOf(
                Leaf(
                    ChatInstructionCopy.SUGGEST,
                    ChatInstructionCopy.SUGGEST_OFF,
                    "",
                    Target(Kind.SUGGEST, "body"),
                    empty = true
                )
            )
        }
        return listOf(
            Category(
                Kind.ROLE,
                ChatInstructionCopy.ROLE,
                ChatInstructionCopy.ROLE_SUB,
                listOf(Leaf(ChatInstructionCopy.ROLE, ChatInstructionCopy.ROLE_SUB, role, Target(Kind.ROLE, "role")))
            ),
            Category(Kind.USER, ChatInstructionCopy.USER, categoryCount(userLeaves), userLeaves),
            Category(
                Kind.IMAGE,
                ChatInstructionCopy.IMAGE,
                if (image.isEmpty()) ChatInstructionCopy.EMPTY else ChatInstructionCopy.IMAGE_SUB,
                listOf(
                    Leaf(
                        ChatInstructionCopy.IMAGE,
                        if (image.isEmpty()) ChatInstructionCopy.EMPTY else ChatInstructionCopy.IMAGE_SUB,
                        image,
                        Target(Kind.IMAGE, "image"),
                        empty = image.isEmpty()
                    )
                )
            ),
            Category(Kind.TAG, ChatInstructionCopy.TAG, categoryCount(tagLeaves), tagLeaves),
            Category(Kind.MEMORY, ChatInstructionCopy.MEMORY, categoryCount(memoryLeaves), memoryLeaves),
            Category(Kind.SUGGEST, ChatInstructionCopy.SUGGEST, categoryCount(suggestLeaves), suggestLeaves)
        )
    }

    fun visibleRows(categories: List<Category>, expanded: Set<Kind>): List<Row> {
        val rows = mutableListOf<Row>()
        categories.forEach { category ->
            val open = category.kind in expanded
            rows += Row.Group(category, open)
            if (open) category.leaves.forEach { rows += Row.Item(it) }
        }
        return rows
    }

    fun toggleExpanded(expanded: Set<Kind>, kind: Kind): Set<Kind> {
        return if (kind in expanded) expanded - kind else expanded + kind
    }

    fun preview(leaf: Leaf): String {
        if (leaf.empty) return ChatInstructionCopy.EMPTY
        return leaf.body.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
            .ifEmpty { ChatInstructionCopy.EMPTY }
    }

    fun countLine(categories: List<Category>): String {
        val leaves = categories.flatMap { it.leaves }
        return ChatInstructionCopy.count(leaves.count { !it.empty }, leaves.size)
    }

    private fun categoryCount(leaves: List<Leaf>): String {
        val filled = leaves.count { !it.empty }
        return ChatInstructionCopy.categoryCount(filled, leaves.size)
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
    const val SAVE = "保存"
    const val SAVED = "保存した。"

    fun part(index: Int, total: Int): String = "$index / $total"

    fun count(filled: Int, total: Int): String = "使っている $filled / $total"

    fun categoryCount(filled: Int, total: Int): String = "$filled / $total"
}
