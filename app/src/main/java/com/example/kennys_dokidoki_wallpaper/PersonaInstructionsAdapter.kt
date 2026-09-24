package com.example.kennys_dokidoki_wallpaper

import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton

/**
 * ペルソナの指示書一覧。カテゴリー見出しつきの行を並べる。
 * 保存は平坦な items（＝結合順）のままなので、見出しは表示用にその場で組み直す。
 *
 *   [Header 社会的な立場]   ← ここで「ここに追加」＝プールから1本選ぶ
 *     [✓ 指示文      ] ⠿
 *   [Header 容姿・年齢]
 *     [✓ 指示文      ] ⠿
 *   ⠿ を引いて別枠の Header に落とすと、その枠の先頭へ引っ越す
 */
class PersonaInstructionsAdapter(
    private val items: MutableList<PersonaItem>,
    private val categories: () -> List<PersonaCategory>,
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit,
    private val onEdit: (PersonaItem) -> Unit,
    private val onAddTo: (PersonaGroupPolicy.Group) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val rows = mutableListOf<PersonaGroupPolicy.Row>()

    class HeaderHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.tv_persona_group_name)
        val summary: TextView = view.findViewById(R.id.tv_persona_group_summary)
        val add: MaterialButton = view.findViewById(R.id.btn_persona_group_add)
    }

    class EntryHolder(view: View) : RecyclerView.ViewHolder(view) {
        val enabled: CheckBox = view.findViewById(R.id.cb_enabled)
        val content: TextView = view.findViewById(R.id.tv_instruction_content)
        val delete: ImageButton = view.findViewById(R.id.btn_delete_instruction)
        val handle: ImageButton = view.findViewById(R.id.tv_drag_handle)
    }

    /** 現在の items と枠から見出し込みの行を組み直す。 */
    fun paint() {
        rows.clear()
        rows.addAll(PersonaGroupPolicy.rows(items, categories()))
        notifyDataSetChanged()
    }

    /** ドラッグ中は行だけ動かす。平坦な items への反映は離した時にまとめる。 */
    fun onItemMove(fromPosition: Int, toPosition: Int): Boolean {
        val source = rows.getOrNull(fromPosition)
        if (source !is PersonaGroupPolicy.Row.Entry) {
            return false
        }
        val requested = PersonaGroupPolicy.dropIndex(rows, fromPosition, toPosition) ?: return false

        rows.removeAt(fromPosition)
        val insertAt = requested.coerceIn(0, rows.size)
        // 落とした位置の枠を覚えさせるので、離した時に categoryId まで揃う
        val group = PersonaGroupPolicy.groupBefore(rows, insertAt) ?: source.group
        rows.add(insertAt, PersonaGroupPolicy.Row.Entry(group, source.item))
        notifyItemMoved(fromPosition, insertAt)
        return true
    }

    /** 表示中の並び＝結合順を、平坦な列として返す。 */
    fun orderedItems(): List<PersonaItem> = PersonaGroupPolicy.flatten(rows)

    /** 枠をまたいだ移動を、items の並びと categoryId に書き戻す。 */
    fun commitDrag() {
        items.clear()
        items.addAll(PersonaGroupPolicy.flatten(rows))
        paint()
    }

    override fun getItemCount(): Int = rows.size

    override fun getItemViewType(position: Int): Int =
        if (rows[position] is PersonaGroupPolicy.Row.Header) TYPE_HEADER else TYPE_ENTRY

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        if (viewType == TYPE_HEADER) {
            return HeaderHolder(inflater.inflate(R.layout.item_persona_group_header, parent, false))
        }
        return EntryHolder(inflater.inflate(R.layout.item_persona_instruction, parent, false))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is PersonaGroupPolicy.Row.Header -> bindHeader(holder as HeaderHolder, row.group)
            is PersonaGroupPolicy.Row.Entry -> bindEntry(holder as EntryHolder, row.item)
        }
    }

    private fun bindHeader(holder: HeaderHolder, group: PersonaGroupPolicy.Group) {
        holder.name.text = group.name
        holder.summary.text = PersonaGroupPolicy.summary(group)
        holder.add.setOnClickListener { onAddTo(group) }
    }

    private fun bindEntry(holder: EntryHolder, item: PersonaItem) {
        holder.enabled.setOnCheckedChangeListener(null)
        holder.enabled.isChecked = item.isEnabled
        holder.enabled.setOnCheckedChangeListener { _, checked -> item.isEnabled = checked }

        holder.content.text = item.content
        holder.content.alpha = if (item.isEnabled) 1f else MUTED_ALPHA
        holder.content.setOnClickListener { onEdit(item) }

        holder.handle.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                onStartDrag(holder)
            }
            false
        }
        holder.delete.setOnClickListener {
            items.remove(item)
            paint()
        }
    }

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_ENTRY = 1
        const val MUTED_ALPHA = 0.45f
    }
}

/**
 * ドラッグ＆ドロップを管理するタッチヘルパーコールバック。
 * 離した瞬間に commitDrag して、平坦な結合順へ書き戻す。
 */
class PersonaTouchHelperCallback(
    private val adapter: PersonaInstructionsAdapter
) : ItemTouchHelper.Callback() {

    override fun isLongPressDragEnabled(): Boolean = false

    override fun isItemViewSwipeEnabled(): Boolean = false

    override fun getMovementFlags(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder
    ): Int = makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)

    override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder
    ): Boolean = adapter.onItemMove(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

    override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
        super.clearView(recyclerView, viewHolder)
        adapter.commitDrag()
    }
}
