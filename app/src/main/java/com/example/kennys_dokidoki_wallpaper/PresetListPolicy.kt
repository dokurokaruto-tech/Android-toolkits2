package com.example.kennys_dokidoki_wallpaper

/**
 * プリセット一覧は、プロンプトカードと同じく
 * 展開中カテゴリーの末尾に保存用プラスを置く。
 */
object PresetListPolicy {
    sealed class Row {
        data class Header(val title: String) : Row()
        data class Card(val id: String) : Row()
        data class AddNew(val category: String) : Row()
    }

    fun rows(
        categoryOrder: List<String>,
        presets: List<Pair<String, String>>,
        collapsed: Set<String>
    ): List<Row> {
        val grouped = presets.groupBy { it.second }
        val out = mutableListOf<Row>()
        for (category in categoryOrder) {
            out.add(Row.Header(category))
            if (category !in collapsed) {
                grouped[category].orEmpty().forEach { out.add(Row.Card(it.first)) }
                out.add(Row.AddNew(category))
            }
        }
        return out
    }
}
