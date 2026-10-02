package com.example.kennys_dokidoki_wallpaper

/** 選択と範囲選択の起点を、表示位置ではなく画像キーで保持する。 */
class ImageSelection {
    private val selected = linkedSetOf<String>()
    private var anchor: String? = null

    val size: Int get() = selected.size

    fun contains(key: String): Boolean = key in selected

    fun indices(keys: List<String>): List<Int> = keys.indices.filter { keys[it] in selected }

    fun start(keys: List<String>, position: Int) {
        val key = keys.getOrNull(position) ?: return
        selected.add(key)
        anchor = key
    }

    fun toggle(keys: List<String>, position: Int) {
        val key = keys.getOrNull(position) ?: return
        if (!selected.remove(key)) {
            selected.add(key)
            anchor = key
        }
    }

    fun range(keys: List<String>, position: Int) {
        val key = keys.getOrNull(position) ?: return
        val start = keys.indexOf(anchor).takeIf { it >= 0 } ?: position
        selected.addAll(keys.subList(minOf(start, position), maxOf(start, position) + 1))
        anchor = key
    }

    fun all(keys: List<String>) {
        clear()
        selected.addAll(keys)
    }

    fun clear() {
        selected.clear()
        anchor = null
    }

    fun reconcile(keys: List<String>) {
        val available = keys.toHashSet()
        selected.retainAll(available)
        if (anchor !in available) {
            anchor = null
        }
    }

    companion object {
        fun key(uri: String): String {
            val remote = GeneratedImageIdentity.remoteRef(uri) ?: return uri
            return GeneratedImageIdentity.key(remote)
        }
    }
}
