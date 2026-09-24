package com.example.kennys_dokidoki_wallpaper

import android.app.Activity
import android.view.View
import android.widget.Toast
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textview.MaterialTextView

/**
 * 指示書そのものの共有一覧。どの枠から開いても同じ一覧が出る。
 * ここで文・枠・並びを変えると、全ペルソナの指示書一覧にそのまま返る。
 */
object PersonaPoolDialog {

    private const val ACTION_UP = "↑"
    private const val ACTION_DOWN = "↓"
    private const val ACTION_EDIT = "編集"
    private const val ACTION_DELETE = "削除"

    fun show(activity: Activity, onChanged: () -> Unit) {
        val (_, view) = Md3PopupDialog.inflate(activity, R.layout.dialog_persona_list)
        view.findViewById<MaterialTextView>(R.id.tv_persona_list_title).setText(R.string.persona_pool_title)
        view.findViewById<MaterialTextView>(R.id.tv_persona_list_hint).setText(R.string.persona_pool_manage_hint)
        view.findViewById<MaterialTextView>(R.id.tv_persona_list_empty).setText(R.string.persona_pool_empty)
        view.findViewById<MaterialButton>(R.id.btn_persona_list_add).setText(R.string.persona_pool_add)

        val list = view.findViewById<RecyclerView>(R.id.rv_persona_list)
        val empty = view.findViewById<MaterialTextView>(R.id.tv_persona_list_empty)
        val search = view.findViewById<TextInputEditText>(R.id.et_persona_list_search)
        list.layoutManager = LinearLayoutManager(view.context)

        var query = ""
        lateinit var adapter: PersonaActionRowAdapter

        fun visibleEntries(): List<PersonaPoolEntry> {
            val entries = PersonaPool.all()
            if (query.isBlank()) {
                return entries
            }
            val needle = query.trim()
            return entries.filter { it.body.contains(needle, ignoreCase = true) }
        }

        fun paint() {
            val entries = visibleEntries()
            empty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
            adapter.submit(entries.mapIndexed { index, entry ->
                PersonaActionRow(
                    id = entry.id,
                    body = entry.body,
                    // 枠の指定は全ペルソナ共通。ここで見るのがそのまま実態
                    note = activity.getString(R.string.persona_pool_group_note, PersonaCategories.nameOf(entry.categoryId)),
                    actions = actionsOf(index, entries.size)
                )
            })
        }

        fun edit(entry: PersonaPoolEntry) {
            PersonaTextInputDialog.show(
                activity,
                activity.getString(R.string.persona_pool_entry_edit),
                activity.getString(R.string.persona_pool_entry_hint),
                entry.body,
                PersonaTextInputDialog.Mode.BODY
            ) { body ->
                if (!PersonaPool.update(activity, entry.id, body)) {
                    Toast.makeText(activity, R.string.persona_duplicate, Toast.LENGTH_SHORT).show()
                }
                paint()
                onChanged()
            }
        }

        fun handle(row: PersonaActionRow, action: String) {
            val entries = PersonaPool.all()
            val index = entries.indexOfFirst { it.id == row.id }
            if (index < 0) {
                return
            }
            when (action) {
                ACTION_UP -> PersonaPool.move(activity, index, index - 1)
                ACTION_DOWN -> PersonaPool.move(activity, index, index + 1)
                ACTION_EDIT -> edit(entries[index])
                // 行は共通なので、ここでの削除は全ペルソナから外れる
                ACTION_DELETE -> PersonaPool.remove(activity, row.id)
            }
            paint()
            onChanged()
        }

        adapter = PersonaActionRowAdapter(::handle)
        list.adapter = adapter

        search.doAfterTextChanged {
            query = it?.toString().orEmpty()
            paint()
        }
        paint()

        val dialog = Md3PopupDialog.show(activity, view)

        view.findViewById<MaterialButton>(R.id.btn_persona_list_add).setOnClickListener {
            PersonaTextInputDialog.show(
                activity,
                activity.getString(R.string.persona_pool_new_title),
                activity.getString(R.string.persona_pool_new_hint),
                "",
                PersonaTextInputDialog.Mode.BODY
            ) { body ->
                if (PersonaPool.add(activity, body) == null) {
                    Toast.makeText(activity, R.string.persona_duplicate, Toast.LENGTH_SHORT).show()
                    return@show
                }
                paint()
                onChanged()
            }
        }
        view.findViewById<MaterialButton>(R.id.btn_persona_list_close).setOnClickListener {
            dialog.dismiss()
        }
    }

    private fun actionsOf(index: Int, size: Int): List<String> {
        val actions = mutableListOf<String>()
        if (index > 0) {
            actions += ACTION_UP
        }
        if (index < size - 1) {
            actions += ACTION_DOWN
        }
        actions += ACTION_EDIT
        actions += ACTION_DELETE
        return actions
    }
}
