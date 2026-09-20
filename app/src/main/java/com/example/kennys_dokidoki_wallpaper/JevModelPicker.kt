package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import android.util.Log
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.progressindicator.CircularProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * OpenRouterのモデルIDを選ぶ共有ピッカー。
 * AIキャラチャット画面のモデル選択と同じレイアウト・検索・フィルタを使う。
 */
internal object JevModelPicker {
    private data class Entry(val id: String, val name: String, val isFree: Boolean,
                             val contextLength: Int, val pricePerMillion: Double, val created: Long)

    private const val LIST_URL = "https://openrouter.ai/api/v1/models"
    private const val CACHE_KEY = "cached_openrouter_models"

    fun show(activity: AppCompatActivity, title: String, currentId: String?, onPick: (String) -> Unit) {
        val (_, view) = Md3PopupDialog.inflate(activity, R.layout.dialog_model_picker_md3)
        view.findViewById<TextView>(R.id.tv_picker_title).text = title
        view.findViewById<TextView>(R.id.tv_provider).text = "OpenRouter"
        val search = view.findViewById<EditText>(R.id.et_model_search)
        val list = view.findViewById<RecyclerView>(R.id.rv_models)
        val progress = view.findViewById<CircularProgressIndicator>(R.id.progress_models)
        val empty = view.findViewById<TextView>(R.id.tv_empty)
        val count = view.findViewById<TextView>(R.id.tv_count)
        val chipAll = view.findViewById<Chip>(R.id.chip_all)
        val chipFree = view.findViewById<Chip>(R.id.chip_free)
        val btnSort = view.findViewById<MaterialButton>(R.id.btn_sort)
        val btnRefresh = view.findViewById<MaterialButton>(R.id.btn_refresh)

        list.layoutManager = LinearLayoutManager(view.context)
        val dialog = Md3PopupDialog.show(activity, view)

        var entries = loadCached(activity)
        var freeOnly = false
        var sortByDate = true
        var isLoading = false

        fun render() {
            if (isLoading) {
                list.visibility = View.GONE
                empty.visibility = View.GONE
                return
            }
            val query = search.text.toString()
            val filtered = entries.filter {
                (!freeOnly || it.isFree) && ModelSearch.matches(it.name, it.id, query)
            }
            val shown = if (sortByDate) filtered.sortedByDescending { it.created }
                else filtered.sortedBy { it.name.lowercase() }
            count.text = activity.getString(R.string.model_result_count, filtered.size)
            empty.setText(if (entries.isEmpty()) R.string.model_load_empty else R.string.model_search_empty)
            if (shown.isEmpty()) {
                list.visibility = View.GONE
                empty.visibility = View.VISIBLE
                return
            }
            empty.visibility = View.GONE
            list.visibility = View.VISIBLE
            list.adapter = ModelMd3Adapter(
                shown.map { ModelMd3Item(it.id, it.name, it.contextLength, it.isFree, it.pricePerMillion) },
                currentId) { item ->
                (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(search.windowToken, 0)
                onPick(item.id)
                dialog.dismiss()
            }
        }

        fun setLoading(loading: Boolean) {
            isLoading = loading
            if (loading) {
                progress.show()
                empty.visibility = View.GONE
            } else {
                progress.hide()
            }
            render()
        }

        fun refresh() {
            setLoading(true)
            activity.lifecycleScope.launch(Dispatchers.IO) {
                val fetched = fetch() ?: emptyList()
                val success = fetched.isNotEmpty()
                if (success) {
                    activity.getSharedPreferences("settings", Context.MODE_PRIVATE)
                        .edit().putString(CACHE_KEY, fetched.toString()).apply()
                }
                withContext(Dispatchers.Main) {
                    if (!dialog.isShowing) return@withContext
                    if (success) entries = parse(fetched)
                    setLoading(false)
                    if (!success && entries.isEmpty()) {
                        Toast.makeText(activity, "モデルリストの取得に失敗しました。", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        val filterClick = View.OnClickListener {
            freeOnly = chipFree.isChecked
            render()
        }
        chipAll.setOnClickListener(filterClick)
        chipFree.setOnClickListener(filterClick)

        fun updateSortLabel() {
            btnSort.text = if (sortByDate) "新着順" else "名前順"
        }
        updateSortLabel()
        btnSort.setOnClickListener {
            sortByDate = !sortByDate
            updateSortLabel()
            render()
        }
        btnRefresh.setOnClickListener { refresh() }
        search.doAfterTextChanged { render() }

        render()
        refresh()
    }

    private fun loadCached(activity: AppCompatActivity): List<Entry> {
        val raw = activity.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getString(CACHE_KEY, null) ?: return emptyList()
        return runCatching { parse(JSONObject(raw)) }.getOrDefault(emptyList())
    }

    private fun parse(raw: JSONObject): List<Entry> {
        val data = raw.getJSONArray("data")
        val result = mutableListOf<Entry>()
        for (i in 0 until data.length()) {
            val obj = data.getJSONObject(i)
            val pricing = obj.optJSONObject("pricing")
            val isFree = (pricing?.optString("prompt") == "0" ||
                pricing?.optDouble("prompt", 1.0) == 0.0) &&
                (pricing?.optString("completion") == "0" ||
                    pricing?.optDouble("completion", 1.0) == 0.0)
            result.add(Entry(
                id = obj.getString("id"),
                name = obj.getString("name"),
                isFree = isFree,
                contextLength = obj.optInt("context_length", 0),
                pricePerMillion = (pricing?.optDouble("prompt", 0.0) ?: 0.0) * 1_000_000.0,
                created = obj.optLong("created", 0)))
        }
        return result
    }

    private suspend fun fetch(): JSONObject? {
        val connection = URL(LIST_URL).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                null
            } else {
                val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                JSONObject(body)
            }
        } catch (error: Exception) {
            Log.e("JevModelPicker", "model list fetch failed", error)
            null
        } finally {
            connection.disconnect()
        }
    }
}
