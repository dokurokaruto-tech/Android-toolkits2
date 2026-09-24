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
 * 指示文のプール。カテゴリーをどれ選んでも同じ一覧が出る（候補は共通）。
 * 登録時に本文をコピーするので、プールを直しても登録済みは動かない。
 */
object PersonaPoolDialog {

    private const val ACTION_UP = "↑"
    private const val ACTION_DOWN = "↓"
    private const val ACTION_EDIT = "編集"
    private const val ACTION_DELETE = "削除"
    private const val ACTION_USE = "登録"

    enum class Mode { MANAGE, REGISTER }

    /** プールの中身そのものを整備する。 */
    fun manage(activity: Activity, working: () -> List<PersonaItem>, onChanged: () -> Unit) {
        show(activity, Mode.MANAGE, "", working, onChanged) {}
    }

    /** カテゴリーヘッダーの「ここに追加」から開く。選んだら登録して閉じる。 */
    fun register(
        activity: Activity,
        group: PersonaGroupPolicy.Group,
        working: () -> List<PersonaItem>,
        onChanged: () -> Unit,
        onPick: (PersonaPoolEntry) -> Unit
    ) {
        show(activity, Mode.REGISTER, group.name, working, onChanged, onPick)
    }

    private fun show(
        activity: Activity,
        mode: Mode,
        groupName: String,
        working: () -> List<PersonaItem>,
        onChanged: () -> Unit,
        onPick: (PersonaPoolEntry) -> Unit
    ) {
        val registering = mode == Mode.REGISTER
        val (_, view) = Md3PopupDialog.inflate(activity, R.layout.dialog_persona_list)
        view.findViewById<MaterialTextView>(R.id.tv_persona_list_title).setText(R.string.persona_pool_title)
        view.findViewById<MaterialTextView>(R.id.tv_persona_list_hint).text = hintOf(activity, mode, groupName)
        view.findViewById<MaterialTextView>(R.id.tv_persona_list_empty).text = emptyOf(activity, registering)
        view.findViewById<MaterialButton>(R.id.btn_persona_list_add)
            .setText(if (registering) R.string.persona_pool_add_register else R.string.persona_pool_add)

        val list = view.findViewById<RecyclerView>(R.id.rv_persona_list)
        val empty = view.findViewById<MaterialTextView>(R.id.tv_persona_list_empty)
        val search = view.findViewById<TextInputEditText>(R.id.et_persona_list_search)
        list.layoutManager = LinearLayoutManager(view.context)

        var query = ""
        lateinit var adapter: PersonaActionRowAdapter
        lateinit var dismiss: () -> Unit

        fun visibleEntries(): List<PersonaPoolEntry> {
            val entries = PersonaPool.all()
            if (query.isBlank()) {
                return entries
            }
            val needle = query.trim()
            return entries.filter { it.body.contains(needle, ignoreCase = true) }
        }

        fun pick(entry: PersonaPoolEntry) {
            onPick(entry)
            dismiss()
        }

        fun paint() {
            val entries = visibleEntries()
            val taken = working().map { it.content.trim() }.toSet()
            empty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
            adapter.submit(entries.mapIndexed { index, entry ->
                PersonaActionRow(
                    id = entry.id,
                    body = entry.body,
                    note = if (registering && entry.body.trim() in taken) {
                        activity.getString(R.string.persona_pool_registered)
                    } else {
                        ""
                    },
                    actions = if (registering) listOf(ACTION_USE) else actionsOf(index, entries.size),
                    onTap = if (registering) { { pick(entry) } } else null
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
            val entry = entries[index]
            when (action) {
                ACTION_UP -> PersonaPool.move(activity, index, index - 1)
                ACTION_DOWN -> PersonaPool.move(activity, index, index + 1)
                ACTION_USE -> pick(entry)
                ACTION_EDIT -> edit(entry)
                // プールから消しても、登録済みの指示はコピー済みなので無傷。
                ACTION_DELETE -> {
                    PersonaPool.remove(activity, row.id)
                    onChanged()
                }
            }
            paint()
        }

        adapter = PersonaActionRowAdapter(::handle)
        list.adapter = adapter

        search.doAfterTextChanged {
            query = it?.toString().orEmpty()
            paint()
        }
        view.findViewById<View>(R.id.til_persona_list_search).visibility = View.VISIBLE
        paint()

        val dialog = Md3PopupDialog.show(activity, view)
        dismiss = { dialog.dismiss() }

        view.findViewById<MaterialButton>(R.id.btn_persona_list_add).setOnClickListener {
            PersonaTextInputDialog.show(
                activity,
                activity.getString(R.string.persona_pool_new_title),
                activity.getString(R.string.persona_pool_new_hint),
                "",
                PersonaTextInputDialog.Mode.BODY
            ) { body ->
                val added = PersonaPool.add(activity, body)
                if (added == null) {
                    Toast.makeText(activity, R.string.persona_duplicate, Toast.LENGTH_SHORT).show()
                    return@show
                }
                if (registering) {
                    pick(added)
                }
                paint()
                onChanged()
            }
        }
        view.findViewById<MaterialButton>(R.id.btn_persona_list_close).setOnClickListener {
            dialog.dismiss()
        }
    }

    private fun hintOf(activity: Activity, mode: Mode, groupName: String): String {
        if (mode == Mode.MANAGE) {
            return activity.getString(R.string.persona_pool_manage_hint)
        }
        return activity.getString(R.string.persona_pool_register_hint, groupName)
    }

    private fun emptyOf(activity: Activity, registering: Boolean): String =
        if (registering) {
            activity.getString(R.string.persona_pool_empty_register)
        } else {
            activity.getString(R.string.persona_pool_empty)
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
