package com.example.kennys_dokidoki_wallpaper

import org.json.JSONArray
import org.json.JSONObject

/**
 * タグ文章をAIで書くときの指示書。
 * 設定画面と同じプロファイルを、タグ編集からも直せる。
 */
object TagInstructionPolicy {
    const val PROFILES_KEY = "ai_system_prompt_profiles"
    const val LEGACY_KEY = "ai_system_prompt"
    const val DEFAULT_PROFILE_NAME = "メイン"

    val DEFAULT_PROMPT = """
            あなたはAIキャラクターチャットのコンテキスト設計におけるスペシャリストです。
            このアプリは、複数の独立した『タグ』をパズルのように組み合わせることで一人のキャラクターを完成させる『非破壊的・プロシージャル・コンテキスト・システム』を採用しています。

            あなたの任務は、指定されたタグの要素を, 以下の制約を遵守しながらAIチャット内に正しく、かつ奥深く『召喚』するための指示書（プロンプト）を書き出すことです。

            【厳守すべき設計指針】
            1. **文字数と簡潔さ**: 300文字から500文字以内で簡潔に記述してください。ただし、その要素が正しく, かつ奥深くチャットの中に反映されるような密度の高い内容にしてください。
            2. **独立性の原則（カプセル化）**: 指定されたタグの 担当領域のみを記述してください。他の領域に干渉することは厳禁です。
            3. **肉体美と官能性の描写**: 身体部位や容姿に関する入力の場合、その質感、形状、魅力を官能的かつ詩的な語彙で描写し、キャラクター設定として汎用性の高い濃厚な内容にしてください。
            4. **直接的な召喚**: AIキャラクターがその設定を深く自己に刻み込み、なりきるための『純粋な設定文』のみを出力してください。挨拶や解説は一切不要です。
            5. **キャラの セリフへの影響防止**: キャラのセリフに直接的な影響を与えないため、例文（セリフ例）は作らないようにしてください。

            【出力例】
            ターゲットタグ: 「ドヤ顔」
            出力例:
            キャラクターは現在の状況と、今自分が身につけている格好に対して、絶対的な自信と強い満足感を抱いています。常に胸を張り、得意げで鼻高々な「ドヤ顔」を浮かべて会話をします。自分の魅力や、今のシチュエーションがいかに自分に有利で似合っているかを隠すことなく全面的にアピールします。会話の端々に「自分のすごさを分かってほしい」「褒められたい」という優越感や自己肯定感の高さがにじみ出ます。
        """.trimIndent()

    data class Profile(val name: String, val content: String)

    fun parse(profilesJson: String?, legacyPrompt: String?): List<Profile> {
        if (!profilesJson.isNullOrBlank()) {
            return runCatching {
                val array = JSONArray(profilesJson)
                buildList {
                    for (index in 0 until array.length()) {
                        val obj = array.optJSONObject(index) ?: continue
                        val name = obj.optString("name").trim()
                        val content = obj.optString("content")
                        if (name.isNotEmpty()) add(Profile(name, content))
                    }
                }
            }.getOrDefault(emptyList())
        }
        val legacy = legacyPrompt?.trim().orEmpty()
        return listOf(Profile(DEFAULT_PROFILE_NAME, legacy.ifEmpty { DEFAULT_PROMPT }))
    }

    fun encode(profiles: List<Profile>): String {
        val array = JSONArray()
        ensureNonEmpty(profiles).forEach { profile ->
            array.put(
                JSONObject().apply {
                    put("name", profile.name)
                    put("content", profile.content)
                }
            )
        }
        return array.toString()
    }

    fun ensureNonEmpty(profiles: List<Profile>): List<Profile> {
        val cleaned = profiles.map { profile ->
            Profile(
                name = profile.name.trim().ifEmpty { DEFAULT_PROFILE_NAME },
                content = profile.content
            )
        }.filter { it.name.isNotEmpty() }
        return cleaned.ifEmpty { listOf(Profile(DEFAULT_PROFILE_NAME, DEFAULT_PROMPT)) }
    }

    fun canDelete(profiles: List<Profile>): Boolean = profiles.size > 1

    fun removeAt(profiles: List<Profile>, index: Int): List<Profile> {
        if (!canDelete(profiles) || index !in profiles.indices) return ensureNonEmpty(profiles)
        return ensureNonEmpty(profiles.filterIndexed { i, _ -> i != index })
    }

    fun selectedIndex(profiles: List<Profile>, selectedName: String?): Int {
        if (profiles.isEmpty()) return 0
        val match = profiles.indexOfFirst { it.name == selectedName }
        return if (match >= 0) match else 0
    }
}
