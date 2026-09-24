package com.example.kennys_dokidoki_wallpaper

/**
 * ペルソナ指示書をカテゴリー単位で束ねる規則。
 * 文と枠は共通の一覧が持ち、ペルソナ側は on/off だけ持つ。表示の束は毎回ここで組み直す。
 *
 *   [共通の一覧] ──┬──groups()──> [Header, Entry, Entry, Header, Entry] ──arrangement()──> 並びと枠
 *   [on/off]  ────┘                        ↑ ここに落とすと、その枠へ引っ越す（全ペルソナに効く）
 */
object PersonaGroupPolicy {

    /** 旧データや、削除済みカテゴリが残す識別子。画面では最後にまとめる。 */
    const val UNGROUPED_ID = ""
    const val UNGROUPED_LABEL = "未分類"

    /** 表示1行ぶん。文と枠は共通、on/off だけがペルソナごとに違う。 */
    data class Line(val entry: PersonaPoolEntry, val on: Boolean)

    /** 1カテゴリぶん。lines はこのカテゴリ内の並び順そのもの。 */
    data class Group(
        val id: String,
        val name: String,
        val lines: MutableList<Line> = mutableListOf()
    ) {
        val enabledCount: Int get() = lines.count { it.on }
    }

    sealed class Row {
        data class Header(val group: Group) : Row()
        data class Entry(val group: Group, val line: Line) : Row()
    }

    /**
     * カテゴリー順 → その中での並び順に並べ替える。
     * 空カテゴリも出す。ここが「追加」の受け口になるので隠さない。
     */
    fun groups(lines: List<Line>, categories: List<PersonaCategory>): List<Group> {
        val buckets = LinkedHashMap<String, Group>()
        categories.forEach { buckets[it.id] = Group(it.id, it.name) }
        val other = Group(UNGROUPED_ID, UNGROUPED_LABEL)

        lines.forEach { line ->
            (buckets[line.entry.categoryId] ?: other).lines.add(line)
        }
        if (other.lines.isNotEmpty()) {
            buckets[UNGROUPED_ID] = other
        }
        return buckets.values.toList()
    }

    fun rows(lines: List<Line>, categories: List<PersonaCategory>): List<Row> =
        groups(lines, categories).flatMap { group ->
            listOf<Row>(Row.Header(group)) + group.lines.map { Row.Entry(group, it) }
        }

    /** 表示行を、共通一覧の並びとして平坦な列に戻す。 */
    fun flatten(rows: List<Row>): List<Line> =
        rows.filterIsInstance<Row.Entry>().map { it.line }

    /** 見た目上の「行のid → 枠」。ドラッグを離した時にこれをまとめ書きする。 */
    fun arrangement(rows: List<Row>): List<Pair<String, String>> =
        rows.filterIsInstance<Row.Entry>().map { it.line.entry.id to it.group.id }

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

    /** ヘッダー右に添える、このペルソナでの有効数の要約。 */
    fun summary(group: Group): String {
        if (group.lines.isEmpty()) {
            return "0件"
        }
        if (group.enabledCount == group.lines.size) {
            return "${group.lines.size}件"
        }
        return "${group.lines.size}件中 ${group.enabledCount}件が有効"
    }
}
