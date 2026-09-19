package com.example.kennys_dokidoki_wallpaper

/**
 * 「エクスプローラーでフォルダを開く」のための変換。
 * DocumentsUI のドキュメントIDはボリューム毎の接頭辞が必要で、
 * アプリ専用の内部領域（/data 配下）はそもそも覗けない。
 */
object FolderExplorerPolicy {

    private const val STORAGE_PREFIX = "/storage/"
    private const val EMULATED = "emulated"

    /**
     * /storage/<volume>/<sub> をドキュメントIDへ。
     * emulated/N は primary:、SDなどはボリュームUUID:。開けない場所は null。
     */
    fun documentId(absolutePath: String): String? {
        if (!absolutePath.startsWith(STORAGE_PREFIX)) return null
        val rest = absolutePath.removePrefix(STORAGE_PREFIX)
        val first = rest.indexOf('/')
        if (first <= 0) return null
        val volume = rest.substring(0, first)
        val sub = rest.substring(first + 1).trimEnd('/')
        if (sub.isEmpty()) return null
        if (volume == EMULATED) {
            val second = sub.indexOf('/')
            if (second <= 0) return null
            return "primary:${sub.substring(second + 1)}"
        }
        return "$volume:$sub"
    }
}
