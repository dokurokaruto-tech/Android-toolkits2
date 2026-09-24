package com.example.kennys_dokidoki_wallpaper

import android.app.Activity
import android.view.View
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textview.MaterialTextView

/**
 * 指示書を並べる枠そのものを管理する MD3 ポップアップ。
 * 追加・改名・↑↓ の並び替え・削除を、この場だけで完結させる。
 */
object PersonaCategoryDialog {

    private const val ACTION_UP = "↑"
    private const val ACTION_DOWN = "↓"
    private const val ACTION_RENAME = "改名"
    private const val ACTION_DELETE = "削除"

    /**
     * @param working 編集中のペルソナの指示。件数表示に読むだけで書き換えない。
     * @param onChanged 枠が変わるたびに外の一覧を塗り替える合図。
     */
    fun show(activity: Activity, working: () -> List<PersonaItem>, onChanged: () -> Unit) {
        val (_, view) = Md3PopupDialog.inflate(activity, R.layout.dialog_persona_list)
        view.findViewById<MaterialTextView>(R.id.tv_persona_list_title).setText(R.string.persona_categories_title)
        view.findViewById<MaterialTextView>(R.id.tv_persona_list_hint).setText(R.string.persona_categories_hint)
        view.findViewById<View>(R.id.til_persona_list_search).visibility = View.GONE
        view.findViewById<MaterialTextView>(R.id.tv_persona_list_empty).setText(R.string.persona_category_empty)
        view.findViewById<MaterialButton>(R.id.btn_persona_list_add).setText(R.string.persona_category_add)

        val list = view.findViewById<RecyclerView>(R.id.rv_persona_list)
        val empty = view.findViewById<MaterialTextView>(R.id.tv_persona_list_empty)
        val add = view.findViewById<MaterialButton>(R.id.btn_persona_list_add)
        list.layoutManager = LinearLayoutManager(view.context)

        lateinit var adapter: PersonaActionRowAdapter

        fun paint(items: List<PersonaItem>) {
            val categories = PersonaCategories.all()
            empty.visibility = if (categories.isEmpty()) View.VISIBLE else View.GONE
            adapter.submit(categories.mapIndexed { index, category ->
                val owned = items.count { it.categoryId == category.id }
                PersonaActionRow(
                    id = category.id,
                    body = "${index + 1}. ${category.name}",
                    note = if (owned == 0) "" else "${owned}件",
                    actions = actionsOf(index, categories.size)
                )
            })
        }

        fun rename(id: String, current: String) {
            PersonaTextInputDialog.show(
                activity,
                activity.getString(R.string.persona_category_rename_title),
                activity.getString(R.string.persona_category_rename_hint),
                current,
                PersonaTextInputDialog.Mode.NAME
            ) { name ->
                if (!PersonaCategories.rename(activity, id, name)) {
                    Toast.makeText(activity, R.string.persona_duplicate, Toast.LENGTH_SHORT).show()
                }
                paint(working())
                onChanged()
            }
        }

        fun handle(row: PersonaActionRow, action: String) {
            val categories = PersonaCategories.all()
            val index = categories.indexOfFirst { it.id == row.id }
            if (index < 0) {
                return
            }
            when (action) {
                ACTION_UP -> PersonaCategories.move(activity, index, index - 1)
                ACTION_DOWN -> PersonaCategories.move(activity, index, index + 1)
                ACTION_RENAME -> rename(row.id, categories[index].name)
                // 枠を消しても文は捨てない。未分類へ落ちるだけなので確認は挟まない。
                ACTION_DELETE -> PersonaCategories.remove(activity, row.id)
            }
            paint(working())
            onChanged()
        }

        adapter = PersonaActionRowAdapter(::handle)
        list.adapter = adapter
        paint(working())

        add.setOnClickListener {
            PersonaTextInputDialog.show(
                activity,
                activity.getString(R.string.persona_category_new_title),
                activity.getString(R.string.persona_category_new_hint),
                "",
                PersonaTextInputDialog.Mode.NAME
            ) { name ->
                if (!PersonaCategories.add(activity, name)) {
                    Toast.makeText(activity, R.string.persona_duplicate, Toast.LENGTH_SHORT).show()
                }
                paint(working())
                onChanged()
            }
        }

        val dialog = Md3PopupDialog.show(activity, view)
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
        actions += ACTION_RENAME
        actions += ACTION_DELETE
        return actions
    }
}
