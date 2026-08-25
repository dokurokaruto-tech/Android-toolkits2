package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections

object TagManager {
    private const val PREFS_NAME = "tag_prefs"
    private const val KEY_CATEGORIES = "tag_categories"
    private const val KEY_PROMPTS = "tag_prompts" // 旧形式：タグ→単一の文章
    private const val KEY_PROMPT_VARIANTS = "tag_prompt_variants" // 新形式：タグ→複数の文章
    private const val KEY_IMPLIED_TAGS = "tag_implied_mappings"
    
    val categories = mutableListOf<TagCategory>()

    /**
     * タグごとの「AI召喚用の文章」のバリエーション。
     * 旧バージョンのデータは、読み込み時に「オリジナル」という名前の1件に移行される。
     */
    val tagPromptVariants = mutableMapOf<String, MutableList<TagPromptVariant>>()
    val tagRemoteCardIds = mutableMapOf<String, String>() // リモートカードIDの紐付け
    
    // タグの継承関係（このタグを持っていたら、自動的に持っているとみなすタグのリスト）
    val impliedTagsMap = mutableMapOf<String, Set<String>>()

    @Volatile
    var isLoaded = false
        private set

    @Volatile
    private var isLoading = false
    
    val allTags: List<String>
        @Synchronized
        get() = categories.flatMap { it.tags }.distinct()

    @Synchronized
    fun loadTags(context: Context, forceReload: Boolean = false) {
        if (isLoaded && !forceReload) {
            Log.d("TagManager", "loadTags: already loaded, skip.")
            return
        }
        if (isLoading) {
            Log.d("TagManager", "loadTags: already loading, skip.")
            return
        }
        isLoading = true
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val json = prefs.getString(KEY_CATEGORIES, null)
            val newCategories = mutableListOf<TagCategory>()
            
            if (json != null) {
                val array = JSONArray(json)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    var name = obj.getString("name")
                    
                    if (name == "作品名" || name == "キャラ名") name = "キャラクター"
                    
                    val category = newCategories.find { it.name == name } ?: TagCategory(name).also { newCategories.add(it) }
                    
                    // 親カテゴリー設定の読み込み
                    category.parentCategoryName = if (obj.has("parentCategoryName")) obj.getString("parentCategoryName") else null

                    val tagsArray = obj.getJSONArray("tags")
                    for (j in 0 until tagsArray.length()) {
                        val t = tagsArray.getString(j)
                        if (!category.tags.contains(t)) category.tags.add(t)
                    }
                }
                
                val essentialCategories = listOf("キャラクター", "衣装", "場所", "ストーリー", "その他")
                essentialCategories.forEach { defName ->
                    if (newCategories.none { it.name == defName }) {
                        newCategories.add(TagCategory(defName))
                    }
                }
            } else {
                newCategories.add(TagCategory("キャラクター"))
                newCategories.add(TagCategory("衣装"))
                newCategories.add(TagCategory("場所"))
                newCategories.add(TagCategory("ストーリー"))
                newCategories.add(TagCategory("その他"))
            }

            categories.clear()
            categories.addAll(newCategories)

            // 新形式（タグ→複数の文章）を読み込む
            var migratedLegacyPrompts = false
            val variantsJson = prefs.getString(KEY_PROMPT_VARIANTS, null)
            tagPromptVariants.clear()
            if (variantsJson != null) {
                val obj = JSONObject(variantsJson)
                for (key in obj.keys()) {
                    val value = obj.get(key)
                    val parsed = parseVariantValue(value)
                    if (parsed.isNotEmpty()) {
                        tagPromptVariants[key] = parsed
                    }
                }
            }

            // 旧形式（タグ→単一の文章）が残っていたら「オリジナル」として移行する
            val promptsJson = prefs.getString(KEY_PROMPTS, null)
            if (promptsJson != null) {
                val obj = JSONObject(promptsJson)
                for (key in obj.keys()) {
                    if (!tagPromptVariants.containsKey(key)) {
                        tagPromptVariants[key] = mutableListOf(
                            TagPromptVariant(TagVariantPolicy.ORIGINAL_NAME, obj.getString(key))
                        )
                        migratedLegacyPrompts = true
                    }
                }
                // 移行済みの旧形式キーは消しておく（消したタグが復活するのを防ぐ）
                prefs.edit().remove(KEY_PROMPTS).apply()
            }

            val remoteIdsJson = prefs.getString("tag_remote_ids", null)
            tagRemoteCardIds.clear()
            if (remoteIdsJson != null) {
                val obj = JSONObject(remoteIdsJson)
                for (key in obj.keys()) {
                    tagRemoteCardIds[key] = obj.getString(key)
                }
            }
            
            val impliedJson = prefs.getString(KEY_IMPLIED_TAGS, null)
            impliedTagsMap.clear()
            if (impliedJson != null) {
                val obj = JSONObject(impliedJson)
                for (key in obj.keys()) {
                    val array = obj.getJSONArray(key)
                    val set = mutableSetOf<String>()
                    for (i in 0 until array.length()) {
                        set.add(array.getString(i))
                    }
                    impliedTagsMap[key] = set
                }
            }
            isLoaded = true
            Log.i("TagManager", "loadTags: successfully loaded ${categories.size} categories.")
            if (migratedLegacyPrompts) {
                // 旧形式から移行した結果を新形式で保存し直す
                try {
                    saveTags(context)
                } catch (e: Exception) {
                    Log.e("TagManager", "Failed to persist migrated tag prompts", e)
                }
            }
        } catch (e: Exception) {
            Log.e("TagManager", "loadTags failed!", e)
        } finally {
            isLoading = false
        }
    }

    /**
     * 保存された値（JSONArray＝新形式 or String＝旧形式）をバリエーションのリストへ変換する。
     */
    private fun parseVariantValue(value: Any?): MutableList<TagPromptVariant> {
        val list = mutableListOf<TagPromptVariant>()
        when (value) {
            is JSONArray -> {
                for (i in 0 until value.length()) {
                    val obj = value.optJSONObject(i) ?: continue
                    val name = obj.optString("name").trim()
                        .ifEmpty { TagVariantPolicy.ORIGINAL_NAME }
                    list.add(TagPromptVariant(name, obj.optString("text")))
                }
            }
            is String -> list.add(TagPromptVariant(TagVariantPolicy.ORIGINAL_NAME, value))
        }
        return list
    }

    /**
     * 強制的にロード済みフラグを立てる（バックアップ復元時などに使用）
     */
    @Synchronized
    fun forceSetLoaded() {
        isLoaded = true
    }

    @Synchronized
    fun saveTags(context: Context) {
        if (!isLoaded) {
            Log.e("TagManager", "saveTags BLOCKED: Data has not been fully loaded yet. Preventing accidental overwrite!")
            return
        }

        if (!BackupManager.validateDataIntegrity(context)) {
            if (categories.size <= 1) {
                Log.e("TagManager", "saveTags blocked by BackupManager due to data integrity failure!")
                BackupManager.restoreLatestAutoBackup(context)
                return
            }
            Log.w("TagManager", "integrity warning, but categories exist. Saving user edits.")
        }

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val array = JSONArray()
        categories.forEach { cat ->
            val obj = JSONObject()
            obj.put("name", cat.name)
            obj.put("tags", JSONArray(cat.tags))
            // 親カテゴリー設定の保存
            if (cat.parentCategoryName != null) {
                obj.put("parentCategoryName", cat.parentCategoryName)
            }
            array.put(obj)
        }
        
        val variantsObj = JSONObject()
        tagPromptVariants.forEach { (tag, variants) ->
            val array = JSONArray()
            variants.forEach { variant ->
                array.put(
                    JSONObject().apply {
                        put("name", variant.name)
                        put("text", variant.text)
                    }
                )
            }
            variantsObj.put(tag, array)
        }

        val remoteIdsObj = JSONObject()
        tagRemoteCardIds.forEach { (tag, id) ->
            remoteIdsObj.put(tag, id)
        }

        val impliedObj = JSONObject()
        impliedTagsMap.forEach { (tag, impliedSet) ->
            impliedObj.put(tag, JSONArray(impliedSet.toList()))
        }
        
        prefs.edit()
            .putString(KEY_CATEGORIES, array.toString())
            .putString(KEY_PROMPT_VARIANTS, variantsObj.toString())
            .putString("tag_remote_ids", remoteIdsObj.toString())
            .putString(KEY_IMPLIED_TAGS, impliedObj.toString())
            .remove(KEY_PROMPTS) // 旧形式は新形式へ移行済みなので残さない
            .apply()

        // セーブ成功後、自動バックアップを作成
        BackupManager.createAutoBackup(context)
    }

    /**
     * 指定されたタグの集合に対して、それらが「自動的に持つタグ」も含めた全てのタグを返す。
     */
    @Synchronized
    fun getEffectiveTags(tags: Set<String>): Set<String> {
        val result = tags.toMutableSet()
        val queue = tags.toMutableList()
        
        var i = 0
        while (i < queue.size) {
            val tag = queue[i]
            impliedTagsMap[tag]?.forEach { implied ->
                if (result.add(implied)) {
                    queue.add(implied)
                }
            }
            i++
        }
        return result
    }

    /**
     * 指定された「最終的なタグの集合」から、継承によって自動付与されるものを除いた、
     * 最小限の「手動タグ」の集合を算出するわ。
     */
    @Synchronized
    fun minimizeTags(fullSet: Set<String>): Set<String> {
        return fullSet.filter { tag ->
            val others = fullSet.toMutableSet().apply { remove(tag) }
            val effectiveOfOthers = getEffectiveTags(others)
            !effectiveOfOthers.contains(tag)
        }.toSet()
    }

    /**
     * 「統一規格」の設定に基づいて、新しく付与されたタグを親カテゴリーのタグに自動登録するわ。
     */
    @Synchronized
    fun autoRegisterUniformImpliedTags(context: Context, imageTags: Set<String>, newlyAddedTags: Set<String>) {
        newlyAddedTags.forEach { newTag ->
            val category = categories.find { it.tags.contains(newTag) }
            val parentCatName = category?.parentCategoryName
            if (parentCatName != null) {
                // 親カテゴリーに属するタグを画像から探すわ
                val parentTags = imageTags.filter { t -> 
                    categories.find { it.name == parentCatName }?.tags?.contains(t) == true 
                }
                
                parentTags.forEach { pTag ->
                    val currentImplied = impliedTagsMap[pTag]?.toMutableSet() ?: mutableSetOf()
                    if (currentImplied.add(newTag)) {
                        impliedTagsMap[pTag] = currentImplied
                    }
                }
            }
        }
        if (newlyAddedTags.isNotEmpty()) {
            saveTags(context)
        }
    }

    @Synchronized
    fun addTag(context: Context, tag: String) {
        val trimmedTag = tag.trim()
        if (trimmedTag.isNotBlank() && !allTags.contains(trimmedTag)) {
            categories.find { it.name == "その他" }?.tags?.add(trimmedTag)
                ?: categories.last().tags.add(trimmedTag)
            saveTags(context)
        }
    }

    @Synchronized
    fun renameTag(context: Context, oldTag: String, newTag: String) {
        if (oldTag == newTag || newTag.isBlank()) return

        // カテゴリ内のタグを置換
        categories.forEach { category ->
            val index = category.tags.indexOf(oldTag)
            if (index != -1) {
                category.tags[index] = newTag
            }
        }

        // プロンプト（バリエーションごと）を移行
        val variants = tagPromptVariants.remove(oldTag)
        if (variants != null) {
            tagPromptVariants[newTag] = variants
        }

        // 継承関係を移行
        val implied = impliedTagsMap.remove(oldTag)
        if (implied != null) {
            impliedTagsMap[newTag] = implied
        }
        // 他のタグから参照されている場合も置換するわ
        impliedTagsMap.forEach { (tag, set) ->
            if (set.contains(oldTag)) {
                val newSet = set.toMutableSet()
                newSet.remove(oldTag)
                newSet.add(newTag)
                impliedTagsMap[tag] = newSet
            }
        }

        saveTags(context)
    }

    /**
     * タグに登録された文章のバリエーション一覧を返す。
     */
    @Synchronized
    fun getTagPromptVariants(tag: String): List<TagPromptVariant> {
        return tagPromptVariants[tag]?.toList() ?: emptyList()
    }

    /**
     * タグの文章バリエーション一覧を丸ごと置き換える。
     */
    @Synchronized
    fun setTagPromptVariants(context: Context, tag: String, variants: List<TagPromptVariant>) {
        val cleaned = TagVariantPolicy.ensureNonEmpty(variants)
        tagPromptVariants[tag] = cleaned.toMutableList()
        saveTags(context)
    }

    /**
     * 旧APIとの互換用：デフォルト（「オリジナル」優先）の文章を保存する。
     */
    @Synchronized
    fun setTagPrompt(context: Context, tag: String, prompt: String) {
        val variants = getTagPromptVariants(tag).toMutableList()
        if (variants.isEmpty()) {
            variants.add(TagPromptVariant(TagVariantPolicy.ORIGINAL_NAME, ""))
        }
        val name = TagVariantPolicy.defaultVariantName(variants)
        val index = variants.indexOfFirst { it.name == name }
        variants[index] = variants[index].copy(text = prompt)
        tagPromptVariants[tag] = variants
        saveTags(context)
    }

    /**
     * 指定した名前のバリエーションの文章だけを書き換える。
     */
    @Synchronized
    fun setTagPromptVariant(context: Context, tag: String, variantName: String?, text: String) {
        val variants = getTagPromptVariants(tag).toMutableList()
        if (variants.isEmpty()) {
            tagPromptVariants[tag] = mutableListOf(TagPromptVariant(TagVariantPolicy.ORIGINAL_NAME, text))
            saveTags(context)
            return
        }
        val index = TagVariantPolicy.resolveIndex(variants, variantName)
        variants[index] = variants[index].copy(text = text)
        tagPromptVariants[tag] = variants
        saveTags(context)
    }

    /**
     * 旧APIとの互換用：デフォルト（「オリジナル」優先）の文章を返す。
     */
    @Synchronized
    fun getTagPrompt(tag: String): String {
        return TagVariantPolicy.resolveText(getTagPromptVariants(tag), null)
    }

    /**
     * 指定した名前のバリエーションの文章を返す。
     * 名前が見つからない場合はデフォルト（先頭）の文章を返す。
     */
    @Synchronized
    fun getTagPrompt(tag: String, variantName: String?): String {
        return TagVariantPolicy.resolveText(getTagPromptVariants(tag), variantName)
    }

    fun estimateTokenCount(text: String): Int {
        var tokens = 0
        var i = 0
        while (i < text.length) {
            val char = text[i]
            if (char.code in 0x4E00..0x9FFF || // Kanji
                char.code in 0x3040..0x309F || // Hiragana
                char.code in 0x30A0..0x30FF || // Katakana
                char.code in 0x30B0..0x30FF || // More Katakana
                char.code in 0xFF00..0xFFEF    // Full-width
            ) {
                tokens += 1
                i++
            } else {
                // English/Symbols: roughly 4 chars per token
                var j = i
                while (j < text.length && j < i + 4) {
                    val nextChar = text[j]
                    if (nextChar.code in 0x4E00..0x9FFF || nextChar.code in 0x3040..0x309F || 
                        nextChar.code in 0x30A0..0x30FF || nextChar.code in 0xFF00..0xFFEF) break
                    j++
                }
                tokens += 1
                i = j
            }
        }
        return tokens
    }

    @Synchronized
    fun setImpliedTags(context: Context, tag: String, impliedTags: Set<String>) {
        if (impliedTags.isEmpty()) {
            impliedTagsMap.remove(tag)
        } else {
            impliedTagsMap[tag] = impliedTags
        }
        saveTags(context)
    }

    @Synchronized
    fun getImpliedTags(tag: String): Set<String> {
        return impliedTagsMap[tag] ?: emptySet()
    }

    // --- カテゴリの編集機能 ---
    
    @Synchronized
    fun addCategory(context: Context, name: String) {
        if (name.isNotBlank() && categories.none { it.name == name }) {
            categories.add(categories.size - 1, TagCategory(name))
            saveTags(context)
        }
    }

    @Synchronized
    fun renameCategory(context: Context, oldName: String, newName: String) {
        val category = categories.find { it.name == oldName }
        if (category != null && newName.isNotBlank() && categories.none { it.name == newName }) {
            val oldParent = category.parentCategoryName
            category.name = newName
            
            // 他のカテゴリーの親設定も更新するわ
            categories.forEach { if (it.parentCategoryName == oldName) it.parentCategoryName = newName }
            
            saveTags(context)
        }
    }

    @Synchronized
    fun deleteCategory(context: Context, name: String) {
        val category = categories.find { it.name == name } ?: return
        
        if (category.tags.isNotEmpty()) {
            var otherCategory = categories.find { it.name == "その他" }
            if (otherCategory == null) {
                otherCategory = TagCategory("その他")
                categories.add(otherCategory)
            }
            for (t in category.tags) {
                if (!otherCategory.tags.contains(t)) {
                    otherCategory.tags.add(t)
                }
            }
        }
        
        // 親カテゴリーとして参照されていた場合は解除するわ
        categories.forEach { if (it.parentCategoryName == name) it.parentCategoryName = null }
        
        categories.remove(category)
        saveTags(context)
    }

    @Synchronized
    fun setParentCategory(context: Context, categoryName: String, parentName: String?) {
        val category = categories.find { it.name == categoryName }
        if (category != null) {
            category.parentCategoryName = parentName
            saveTags(context)
        }
    }

    @Synchronized
    fun moveCategoryUp(context: Context, name: String) {
        val index = categories.indexOfFirst { it.name == name }
        if (index > 0) {
            Collections.swap(categories, index, index - 1)
            saveTags(context)
        }
    }

    @Synchronized
    fun moveCategoryDown(context: Context, name: String) {
        val index = categories.indexOfFirst { it.name == name }
        if (index >= 0 && index < categories.size - 1) {
            Collections.swap(categories, index, index + 1)
            saveTags(context)
        }
    }

    @Synchronized
    fun deleteTag(context: Context, tag: String) {
        // カテゴリから削除
        categories.forEach { it.tags.remove(tag) }
        
        // プロンプト（バリエーションごと）から削除
        tagPromptVariants.remove(tag)
        
        // リモートカード紐付けから削除
        tagRemoteCardIds.remove(tag)
        
        // 継承関係から削除
        impliedTagsMap.remove(tag)
        impliedTagsMap.forEach { (key, set) ->
            if (set.contains(tag)) {
                val newSet = set.toMutableSet()
                newSet.remove(tag)
                impliedTagsMap[key] = newSet
            }
        }
        
        saveTags(context)
    }

    /**
     * 画像データやプロンプトカードに残っている「迷子のタグ」を探し出して、
     * 「復旧されたタグ」カテゴリーに安全に復元するわよ！✨
     */
    @Synchronized
    fun resurrectHiddenTags(context: Context): Int {
        val existingTags = allTags.toSet()
        val tagsOnImages = DataManager.allImages.flatMap { it.tags }.toSet()
        val tagsOnCards = PromptCardManager.promptCards.flatMap { it.appliedTags }.toSet()
        val allOrphanTags = (tagsOnImages + tagsOnCards) - existingTags
        
        if (allOrphanTags.isEmpty()) return 0
        
        var targetCategory = categories.find { it.name == "復旧されたタグ" }
        if (targetCategory == null) {
            targetCategory = TagCategory("復旧されたタグ")
            val otherIndex = categories.indexOfFirst { it.name == "その他" }
            if (otherIndex != -1) {
                categories.add(otherIndex, targetCategory)
            } else {
                categories.add(targetCategory)
            }
        }
        
        var count = 0
        allOrphanTags.forEach { tag ->
            val trimmed = tag.trim()
            if (trimmed.isNotBlank() && !targetCategory.tags.contains(trimmed)) {
                targetCategory.tags.add(trimmed)
                count++
            }
        }
        
        if (count > 0) {
            saveTags(context)
        }
        return count
    }
}
