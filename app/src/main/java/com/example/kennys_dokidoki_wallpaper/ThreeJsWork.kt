package com.example.kennys_dokidoki_wallpaper

/**
 * アプリに組み込まれた three.js 作品の目録。
 *
 * ユーザーが追加するのではなく、最初からアプリに同梱される要素。
 * id は assets/threejs/works/<id>.json（three.js Editor 書き出し）に対応する。
 * 壁紙・セット一覧・アルバム詳細はすべてこの目録を唯一の供給源として参照する。
 */
enum class ThreeJsWork(val id: String, val title: String) {
    MIRROR_SPHERE("32", "ミラースフィア"),
    INSTANCE_FLOW("36", "インスタンスフロー"),
    TORUS_ORBIT("38", "トーラスオービット");

    val assetPath: String
        get() = "threejs/works/$id.json"

    val posterPath: String
        get() = "threejs/works/posters/$id.webp"

    // Glide でそのまま読み込める assets URI。
    val posterUri: String
        get() = "file:///android_asset/$posterPath"

    companion object {
        val count: Int
            get() = entries.size

        fun at(index: Int): ThreeJsWork = entries[index.coerceIn(0, entries.lastIndex)]

        fun byId(id: String?): ThreeJsWork? = entries.find { it.id == id }
    }
}
