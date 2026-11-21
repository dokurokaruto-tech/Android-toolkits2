package com.example.kennys_dokidoki_wallpaper

import android.content.SharedPreferences
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class ChatReasoningPolicyTest {

    @Test
    fun detectsSupportFromOpenRouterMetadataParameters() {
        val cached = """
            {"data":[
                {"id":"vendor/custom-thinker","name":"Custom Thinker","supported_parameters":["temperature","reasoning"]},
                {"id":"vendor/plain-chat","name":"Plain Chat","supported_parameters":["temperature","max_tokens"]},
                {"id":"deepseek/deepseek-r1","name":"DeepSeek R1","supported_parameters":["temperature"]}
            ]}
        """.trimIndent()

        assertTrue(ChatReasoningPolicy.supportsOpenRouter("vendor/custom-thinker", cached))
        assertFalse(ChatReasoningPolicy.supportsOpenRouter("vendor/plain-chat", cached))
        // Metadata explicitly omits reasoning parameter even if ID matches heuristic
        assertFalse(ChatReasoningPolicy.supportsOpenRouter("deepseek/deepseek-r1", cached))
    }

    @Test
    fun fallsBackToModelIdHeuristicsWhenMetadataMissing() {
        assertTrue(ChatReasoningPolicy.supportsOpenRouter("deepseek/deepseek-r1:free", null))
        assertTrue(ChatReasoningPolicy.supportsOpenRouter("qwen/qwq-32b", null))
        assertTrue(ChatReasoningPolicy.supportsOpenRouter("qwen/qwen3-30b-a3b", null))
        assertTrue(ChatReasoningPolicy.supportsOpenRouter("openai/o3-mini", null))
        assertTrue(ChatReasoningPolicy.supportsOpenRouter("google/gemini-2.5-flash", null))
        assertFalse(ChatReasoningPolicy.supportsOpenRouter("openai/gpt-4o-mini", null))
        assertFalse(ChatReasoningPolicy.supportsOpenRouter("x-ai/grok-4-1-fast-non-reasoning", null))
    }

    @Test
    fun appliesReasoningObjectOnlyForSupportedOpenRouterModels() {
        val cached = """
            {"data":[
                {"id":"model/reasoner","name":"Reasoner","supported_parameters":["reasoning"]},
                {"id":"model/plain","name":"Plain","supported_parameters":["temperature"]}
            ]}
        """.trimIndent()
        val prefs = preferences(
            strings = mutableMapOf("cached_openrouter_models" to cached),
            booleans = mutableMapOf(ChatReasoningPolicy.PREF_KEY to true)
        )

        val supportedEnabled = JSONObject()
        ChatReasoningPolicy.applyOpenRouter(prefs, "model/reasoner", supportedEnabled)
        val reasoningOn = supportedEnabled.getJSONObject("reasoning")
        assertTrue(reasoningOn.getBoolean("enabled"))
        assertTrue(reasoningOn.getBoolean("exclude"))

        ChatReasoningPolicy.toggle(prefs)
        val supportedDisabled = JSONObject()
        ChatReasoningPolicy.applyOpenRouter(prefs, "model/reasoner", supportedDisabled)
        val reasoningOff = supportedDisabled.getJSONObject("reasoning")
        assertFalse(reasoningOff.getBoolean("enabled"))
        assertTrue(reasoningOff.getBoolean("exclude"))

        val unsupported = JSONObject()
        ChatReasoningPolicy.applyOpenRouter(prefs, "model/plain", unsupported)
        assertFalse(unsupported.has("reasoning"))
    }

    @Test
    fun resolvesCurrentTargetAcrossCloudAndLocalEngines() {
        val cached = """
            {"data":[
                {"id":"qwen/qwq-32b","name":"QwQ","supported_parameters":["reasoning"]}
            ]}
        """.trimIndent()
        val strings = mutableMapOf<String, String?>(
            "chat_llm_engine" to "CLOUD",
            "chat_cloud_provider" to "OPENROUTER",
            "chat_openrouter_model" to "qwen/qwq-32b",
            "cached_openrouter_models" to cached
        )
        val prefs = preferences(strings = strings, booleans = mutableMapOf())

        assertTrue(ChatReasoningPolicy.isCurrentTargetSupported(prefs, "deepseek/deepseek-v4-flash:free", null))

        strings["chat_cloud_provider"] = "GROK"
        assertFalse(ChatReasoningPolicy.isCurrentTargetSupported(prefs, "deepseek/deepseek-v4-flash:free", null))

        strings["chat_llm_engine"] = "LOCAL"
        assertTrue(ChatReasoningPolicy.isCurrentTargetSupported(prefs, "deepseek/deepseek-v4-flash:free", "Qwen3-8B-Q4_K_M"))
        assertFalse(ChatReasoningPolicy.isCurrentTargetSupported(prefs, "deepseek/deepseek-v4-flash:free", "Gemma-2-2B-It"))
    }

    @Test
    fun injectsEmptyThinkBlockForLocalReasoningModelWhenDisabled() {
        val base = "<|im_start|>assistant\n"
        assertEquals(
            base,
            ChatReasoningPolicy.applyLocalPrompt(base, "Qwen3-8B", ChatReasoningPolicy.Mode.ENABLED)
        )
        assertEquals(
            "<|im_start|>assistant\n<think>\n</think>\n",
            ChatReasoningPolicy.applyLocalPrompt(base, "Qwen3-8B", ChatReasoningPolicy.Mode.DISABLED)
        )
        assertEquals(
            base,
            ChatReasoningPolicy.applyLocalPrompt(base, "Gemma-2-2B", ChatReasoningPolicy.Mode.DISABLED)
        )
    }

    @Test
    fun stripsClosedAndInProgressThinkBlocks() {
        assertEquals(
            "",
            ChatReasoningPolicy.stripThinkBlocks("<think>考え中…まだ途中")
        )
        assertEquals(
            "こんにちは！元気？",
            ChatReasoningPolicy.stripThinkBlocks("<think>\n挨拶を考えよう\n</think>\nこんにちは！元気？")
        )
        val parsed = ChatSuggestionParser.parse(
            "<think>内部推論</think>\nやっほー！\n<<<SUGGESTIONS>>>\nA: おはよう\nB: こんにちは\nC: こんばんは\n<<</SUGGESTIONS>>>"
        )
        assertEquals("やっほー！", parsed.cleanText)
        assertEquals("おはよう", parsed.suggestionA)
    }

    private fun preferences(
        strings: MutableMap<String, String?>,
        booleans: MutableMap<String, Boolean>
    ): SharedPreferences {
        val editorType = SharedPreferences.Editor::class.java
        val editor = Proxy.newProxyInstance(editorType.classLoader, arrayOf(editorType)) { proxy, method, args ->
            when (method.name) {
                "putString" -> {
                    strings[args!![0] as String] = args[1] as String?
                    proxy
                }
                "putBoolean" -> {
                    booleans[args!![0] as String] = args[1] as Boolean
                    proxy
                }
                "apply" -> null
                else -> error("Unexpected editor call: ${method.name}")
            }
        }
        val prefsType = SharedPreferences::class.java
        return Proxy.newProxyInstance(prefsType.classLoader, arrayOf(prefsType)) { _, method, args ->
            when (method.name) {
                "edit" -> editor
                "getString" -> strings[args!![0] as String] ?: args[1]
                "getBoolean" -> booleans[args!![0] as String] ?: (args[1] as Boolean)
                else -> error("Unexpected preference call: ${method.name}")
            }
        } as SharedPreferences
    }
}
