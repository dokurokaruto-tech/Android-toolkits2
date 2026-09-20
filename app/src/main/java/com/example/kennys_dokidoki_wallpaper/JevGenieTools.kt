package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * ジーニーの実行層。カタログ構築・OpenRouter呼び出し・既存データへの適用を担う。
 * UI(JevGenieDialog)はこの層とJevGeniePolicyを通じてだけデータに触る。
 */
internal object JevGenieTools {
    private const val COMPLETIONS_URL = "https://openrouter.ai/api/v1/chat/completions"
    private const val CONNECT_TIMEOUT = 15_000
    private const val READ_TIMEOUT = 120_000

    fun cardRefs(): List<JevGenieCardRef> = PromptCardManager.promptCards.map {
        JevGenieCardRef(it.id, it.label, it.category, it.mainPrompt, it.negativePrompt)
    }

    fun tagRefs(): List<JevGenieTagRef> {
        // タグは未ロードだと空カタログになるため、ロード済みの時だけ出す
        if (!TagManager.isLoaded) {
            return emptyList()
        }
        return TagManager.allTags.map {
            JevGenieTagRef(it, TagManager.getTagPrompt(it), TagManager.getTagPromptVariants(it))
        }
    }

    /** OpenRouterのchat/completionsを叩く。利用数カウントは他画面と同じ規則で行う。 */
    suspend fun request(context: Context, body: JSONObject): String = withContext(Dispatchers.IO) {
        val key = OpenRouterManager.getActiveApiKey(context)
            ?: throw IllegalStateException("OpenRouter APIキーが設定されていません")
        val connection = URL(COMPLETIONS_URL).openConnection() as HttpURLConnection
        val result = try {
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT
            connection.readTimeout = READ_TIMEOUT
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $key")
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }
            val code = connection.responseCode
            val source = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = source?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                throw IOException("OpenRouter HTTP $code: ${text.take(300)}")
            }
            JSONObject(text).getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content")
        } finally {
            connection.disconnect()
        }
        OpenRouterManager.countFreeUsage(context, key, body.optString("model"))
        result
    }

    /** ライブなカードオブジェクトへ反映して保存する。対象が消えていたらfalse。 */
    fun applyCard(context: Context, plan: JevGeniePlan.EditCard): Boolean {
        val live = PromptCardManager.promptCards.firstOrNull { it.id == plan.target.id }
            ?: PromptCardManager.promptCards.firstOrNull {
                JevElementPolicy.sameName(it.label, plan.target.label)
            }
            ?: return false
        live.mainPrompt = plan.newMain
        live.negativePrompt = plan.newNegative
        PromptCardManager.saveCards(context)
        return true
    }

    fun applyTag(context: Context, plan: JevGeniePlan.EditTag): Boolean {
        val exists = TagManager.allTags.any { JevElementPolicy.sameName(it, plan.target.name) }
        if (!exists) {
            return false
        }
        if (plan.variant == null) {
            TagManager.setTagPrompt(context, plan.target.name, plan.newText)
        } else {
            TagManager.setTagPromptVariant(context, plan.target.name, plan.variant, plan.newText)
        }
        return true
    }
}
