package com.example.kennys_dokidoki_wallpaper

/**
 * チャット画面から「今のイメージセット」の画像を 3 列で選ぶための一覧。
 */
object ChatSetImagePicker {
    const val GRID_COLUMNS = 3

    fun imagesForActiveSet(
        allImages: List<ImageEntry>,
        sets: List<ImageSet>,
        activeSetName: String?
    ): List<ImageEntry> {
        if (activeSetName.isNullOrBlank()) return emptyList()
        val set = sets.find { it.name == activeSetName } ?: return emptyList()
        return set.filterImages(allImages).filter { it.isActive }
    }

    fun indexOfUri(images: List<ImageEntry>, uri: String?): Int {
        if (uri.isNullOrBlank()) return -1
        return images.indexOfFirst { it.uri.toString() == uri }
    }

    fun indexOfId(ids: List<String>, currentId: String?): Int {
        if (currentId.isNullOrBlank()) return -1
        return ids.indexOf(currentId)
    }

    fun canSelect(count: Int, index: Int): Boolean = index in 0 until count
}
