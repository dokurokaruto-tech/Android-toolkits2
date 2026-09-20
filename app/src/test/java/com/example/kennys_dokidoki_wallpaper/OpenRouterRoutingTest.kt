package com.example.kennys_dokidoki_wallpaper

import android.content.SharedPreferences
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.lang.reflect.Proxy

class OpenRouterRoutingTest {
    @Test
    fun pinsProviderWithoutFallback() {
        val routing = OpenRouterRouting.provider("deepinfra/turbo")!!
        assertEquals("deepinfra/turbo", routing.getJSONArray("only").getString(0))
        assertEquals("deepinfra/turbo", routing.getJSONArray("order").getString(0))
        assertEquals(1, routing.getJSONArray("only").length())
        assertFalse(routing.getBoolean("allow_fallbacks"))
    }

    @Test
    fun automaticOmitsRouting() {
        assertNull(OpenRouterRouting.provider(null))
        assertNull(OpenRouterRouting.provider("  "))
    }

    @Test
    fun selectionIsModelScoped() {
        assertNull(OpenRouterRouting.matchingTag("model/b", "model/a", "provider"))
        assertNull(OpenRouterRouting.matchingTag("model/a:free", "model/a", "provider"))
        assertNull(OpenRouterRouting.matchingTag("model/a", null, "provider"))
        assertEquals("provider", OpenRouterRouting.matchingTag("model/a", "model/a", "provider"))
    }

    @Test
    fun savesAndRoutes() {
        val prefs = preferences()
        OpenRouterRouting.save(prefs, "model/a", "deepinfra/turbo")
        assertEquals("model/a", prefs.getString("chat_openrouter_model", null))
        assertEquals("CLOUD", prefs.getString("chat_llm_engine", null))
        assertEquals("OPENROUTER", prefs.getString("chat_cloud_provider", null))
        assertEquals("deepinfra/turbo", OpenRouterRouting.selected(prefs, "model/a"))
        val request = JSONObject()
        OpenRouterRouting.apply(prefs, "model/a", request)
        assertEquals("deepinfra/turbo", request.getJSONObject("provider").getJSONArray("only").getString(0))
        val otherModel = JSONObject()
        OpenRouterRouting.apply(prefs, "model/b", otherModel)
        assertFalse(otherModel.has("provider"))
    }

    @Test
    fun autoClearsProvider() {
        val prefs = preferences()
        OpenRouterRouting.save(prefs, "model/a", "provider")
        OpenRouterRouting.save(prefs, "model/a", null)
        assertNull(OpenRouterRouting.selected(prefs, "model/a"))
        val request = JSONObject()
        OpenRouterRouting.apply(prefs, "model/a", request)
        assertFalse(request.has("provider"))
    }

    private fun preferences(): SharedPreferences {
        val values = mutableMapOf<String, String?>()
        val editorType = SharedPreferences.Editor::class.java
        val editor = Proxy.newProxyInstance(editorType.classLoader, arrayOf(editorType)) { proxy, method, args ->
            when (method.name) {
                "putString" -> {
                    values[args!![0] as String] = args[1] as String?
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
                "getString" -> values[args!![0] as String] ?: args[1]
                else -> error("Unexpected preference call: ${method.name}")
            }
        } as SharedPreferences
    }
}
