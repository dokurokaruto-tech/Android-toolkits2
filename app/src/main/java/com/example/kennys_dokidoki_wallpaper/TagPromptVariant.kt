package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import org.json.JSONObject

/**
 * 1つのタグに格納される「AI召喚用の文章」のバリエーション。
 *
 * 従来は1タグ＝1文章だったが、同じキャラでも「優しい性格」「いじめっ子な性格」のように
 * 複数の文章（性格）を1つのタグに格納できるようになった。
 * タグ編集画面ではこの1つ1つが横長のボタンとして表示され、
 * キャラチャットの最初にどれを発動させるかを選べる。
 */
data class TagPromptVariant(
    val name: String,
    val text: String
)

/**
 * タグ文章バリエーションに関するルール集（UIから切り離した純粋関数が中心）。
 */
object TagVariantPolicy {
    /** 旧形式から移行した文章に付ける名前。 */
    const val ORIGINAL_NAME = "オリジナル"

    /** キャラチャット側で「どの性格を発動させるか」の選択結果を保存するprefs名。 */
    const val SELECTION_PREFS = "tag_variant_selection"

    /** 旧形式（単一の文章）を、オリジナル1件だけのバリエーションに変換する。 */
    fun parseLegacyPrompt(raw: String?): List<TagPromptVariant> =
        listOf(TagPromptVariant(ORIGINAL_NAME, raw.orEmpty()))

    /** 空リストや名前の欠けた要素を、安全な形に整える。 */
    fun ensureNonEmpty(variants: List<TagPromptVariant>): List<TagPromptVariant> {
        val cleaned = variants.map { variant ->
            TagPromptVariant(
                name = variant.name.trim().ifEmpty { ORIGINAL_NAME },
                text = variant.text
            )
        }
        return cleaned.ifEmpty { listOf(TagPromptVariant(ORIGINAL_NAME, "")) }
    }

    /** 何も選ばれていない時に使うバリエーション名。「オリジナル」があれば優先する。 */
    fun defaultVariantName(variants: List<TagPromptVariant>): String {
        if (variants.isEmpty()) return ORIGINAL_NAME
        return variants.firstOrNull { it.name == ORIGINAL_NAME }?.name ?: variants.first().name
    }

    /** 選択名に対応するバリエーションの位置。見つからなければ先頭。 */
    fun resolveIndex(variants: List<TagPromptVariant>, selectedName: String?): Int {
        if (variants.isEmpty()) return 0
        val match = variants.indexOfFirst { it.name == selectedName }
        return if (match >= 0) match else 0
    }

    /** 選択名を、実在するバリエーション名へ解決する。 */
    fun resolveName(variants: List<TagPromptVariant>, selectedName: String?): String {
        if (variants.isEmpty()) return ORIGINAL_NAME
        return variants[resolveIndex(variants, selectedName)].name
    }

    /** 選択名に対応する文章を返す。 */
    fun resolveText(variants: List<TagPromptVariant>, selectedName: String?): String {
        if (variants.isEmpty()) return ""
        return variants[resolveIndex(variants, selectedName)].text
    }

    /** 2つ以上文章が登録されていれば、チャット開始時に選択UIを出す。 */
    fun hasMultiple(variants: List<TagPromptVariant>): Boolean = variants.size >= 2

    /** 新しく追加するバリエーションの初期名（「設定 2」「設定 3」…）。 */
    fun nextVariantName(variants: List<TagPromptVariant>): String {
        val existing = variants.map { it.name.trim() }.filter { it.isNotEmpty() }.toSet()
        var n = variants.size + 1
        while (true) {
            val candidate = "設定 $n"
            if (candidate !in existing) return candidate
            n++
        }
    }

    /** 最後の1つは削除できない。 */
    fun canDelete(variants: List<TagPromptVariant>): Boolean = variants.size > 1

    /** 指定位置を削除する（最後の1つは守られる）。 */
    fun removeAt(variants: List<TagPromptVariant>, index: Int): List<TagPromptVariant> {
        if (index !in variants.indices || !canDelete(variants)) return ensureNonEmpty(variants)
        return ensureNonEmpty(variants.filterIndexed { i, _ -> i != index })
    }

    /**
     * 指定タグ群のうち、複数の文章を持つタグだけを元の順序のまま返す。
     * チャット開始時の選択UIはこのタグたちだけを対象にする。
     */
    fun multiVariantTags(
        tags: List<String>,
        variantsByTag: (String) -> List<TagPromptVariant>
    ): List<String> = tags.filter { hasMultiple(variantsByTag(it)) }

    /** 画像ごとに保存された「タグ→性格名」の選択結果を読み込む。 */
    fun loadSelection(context: Context, imageKey: String): Map<String, String> {
        if (imageKey.isBlank()) return emptyMap()
        val prefs = context.getSharedPreferences(SELECTION_PREFS, Context.MODE_PRIVATE)
        val json = prefs.getString(imageKey, null) ?: return emptyMap()
        return runCatching {
            val obj = JSONObject(json)
            val result = mutableMapOf<String, String>()
            obj.keys().forEach { key ->
                val name = obj.optString(key, "")
                if (name.isNotBlank()) result[key] = name
            }
            result
        }.getOrDefault(emptyMap())
    }

    /** 画像ごとに「タグ→性格名」の選択結果を保存する。 */
    fun saveSelection(context: Context, imageKey: String, selection: Map<String, String>) {
        if (imageKey.isBlank()) return
        val obj = JSONObject()
        selection.forEach { (tag, name) -> if (name.isNotBlank()) obj.put(tag, name) }
        context.getSharedPreferences(SELECTION_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(imageKey, obj.toString())
            .apply()
    }

    /** 画像ごとの選択結果を消す（チャットを完全にやり直すときなど）。 */
    fun clearSelection(context: Context, imageKey: String) {
        if (imageKey.isBlank()) return
        context.getSharedPreferences(SELECTION_PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(imageKey)
            .apply()
    }
}
