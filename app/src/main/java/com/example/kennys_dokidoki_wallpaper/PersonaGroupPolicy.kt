package com.example.kennys_dokidoki_wallpaper

/**
 * ペルソナ指示書をカテゴリー単位で束ねる規則。
 * 保存は常に平坦な items（＝結合順）で、group は表示のためにその場で作る。
 * 文がどの枠に属するかはプール側が全局で持つので、ここはその写しを見るだけ。
 *
 *   bindings ──┐
 *              ├─groups()──> [Header, Entry, Entry, Header, Entry] ──flatten()──> items
 *   items ─────┘                     ↑ ここに落とすと、その枠へ引っ越す
 */
object PersonaGroupPolicy {

    /** 旧データや、削除済みカテゴリが残す識別子。画面では最後にまとめる。 */
    const val UNGROUPED_ID = ""
    const val UNGROUPED_LABEL = "未分類"

    /** 1カテゴリぶん。items はこのカテゴリ内の並び順そのもの。 */
    data class Group(
        val id: String,
        val name: String,
        val items: MutableList<PersonaItem> = mutableListOf()
    ) {
        val enabledCount: Int get() = items.count { it.isEnabled }
    }

    sealed class Row {
        data class Header(val group: Group) : Row()
        data class Entry(val group: Group, val item: PersonaItem) : Row()
    }

    /**
     * カテゴリー順 → その中での並び順に並べ替える。
     * 空カテゴリも出す。ここが「追加」の受け口になるので隠さない。
     */
    /** 本文（前後の空白は無視）が属する枠。プールに無い文は未分類。 */
    fun categoryOf(bindings: Map<String, String>, body: String): String =
        bindings[body.trim()].orEmpty()

    fun groups(
        items: List<PersonaItem>,
        categories: List<PersonaCategory>,
        categoryOf: (PersonaItem) -> String
    ): List<Group> {
        val buckets = LinkedHashMap<String, Group>()
        categories.forEach { buckets[it.id] = Group(it.id, it.name) }
        val other = Group(UNGROUPED_ID, UNGROUPED_LABEL)

        items.forEach { item ->
            (buckets[categoryOf(item)] ?: other).items.add(item)
        }
        if (other.items.isNotEmpty()) {
            buckets[UNGROUPED_ID] = other
        }
        return buckets.values.toList()
    }

    fun rows(
        items: List<PersonaItem>,
        categories: List<PersonaCategory>,
        categoryOf: (PersonaItem) -> String
    ): List<Row> = groups(items, categories, categoryOf).flatMap { group ->
        listOf<Row>(Row.Header(group)) + group.items.map { Row.Entry(group, it) }
    }

    /** 表示行を結合順の平坦リストへ戻す。 */
    fun flatten(rows: List<Row>): List<PersonaItem> =
        rows.filterIsInstance<Row.Entry>().map { it.item }

    /** 見た目上の「文 → 枠」。ここをプールへ書き戻せば、全ペルソナに同じ束縛が効く。 */
    fun bindings(rows: List<Row>): Map<String, String> =
        rows.filterIsInstance<Row.Entry>()
            .associate { entry -> entry.item.content.trim() to entry.group.id }

    /**
     * from の行を to に落とすときの、1行除けた後の並びでの挿入位置。
     * ヘッダーに落とせばその枠の先頭、行に落とせばその位置になる。
     */
    fun dropIndex(rows: List<Row>, from: Int, to: Int): Int? {
        val target = rows.getOrNull(to) ?: return null
        val raw = when (target) {
            is Row.Header -> if (from < to) to else to + 1
            is Row.Entry -> to
        }
        return raw.coerceIn(0, rows.size - 1)
    }

    /** index の直前にある枠。見出しを跨いだ行はその枠へ引っ越す。 */
    fun groupBefore(rows: List<Row>, index: Int): Group? = when (val row = rows.getOrNull(index - 1)) {
        is Row.Header -> row.group
        is Row.Entry -> row.group
        null -> null
    }

    /** ヘッダー右に添える、件数の要約。 */
    fun summary(group: Group): String {
        if (group.items.isEmpty()) {
            return "0件"
        }
        if (group.enabledCount == group.items.size) {
            return "${group.items.size}件"
        }
        return "${group.items.size}件中 ${group.enabledCount}件が有効"
    }
}
