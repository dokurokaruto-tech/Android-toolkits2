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
 * ペルソナの指示書一覧。全ペルソナで共有している行を、カテゴリー見出しつきで並べる。
 * 文・枠・並びは共通の一覧が持つので、ここでの変更は他のペルソナにもそのまま返る。
 * ペルソナごとに違うのはチェック（on/off）だけ。
 *
 *   [Header 社会的な立場]   ← ＋ でこの枠に新しい行を作る（やっぱり全ペルソナ共通）
 *     [✓ 共通の指示文    ] ⠿
 *   [Header 容姿・年齢]
 *     [✓ 共通の指示文    ] ⠿
 *   ⠿ を引いて別枠の Header に落とすと、一覧ごとその枠へ書き換わる
 */
class PersonaInstructionsAdapter(
    private val lines: () -> List<PersonaGroupPolicy.Line>,
    private val categories: () -> List<PersonaCategory>,
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit,
    private val onToggle: (PersonaPoolEntry, Boolean) -> Unit,
    private val onEdit: (PersonaPoolEntry) -> Unit,
    private val onAddTo: (PersonaGroupPolicy.Group) -> Unit,
    private val onArrange: (List<Pair<String, String>>) -> Unit
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

    /** 共通の一覧に、このペルソナの on/off をかぶせて行を組み直す。 */
    fun paint() {
        rows.clear()
        rows.addAll(PersonaGroupPolicy.rows(lines(), categories()))
        notifyDataSetChanged()
    }

    /** ドラッグ中は行だけ動かす。一覧への書き戻しは離した時にまとめる。 */
    fun onItemMove(fromPosition: Int, toPosition: Int): Boolean {
        val source = rows.getOrNull(fromPosition)
        if (source !is PersonaGroupPolicy.Row.Entry) {
            return false
        }
        val requested = PersonaGroupPolicy.dropIndex(rows, fromPosition, toPosition) ?: return false

        rows.removeAt(fromPosition)
        val insertAt = requested.coerceIn(0, rows.size)
        // 落とした位置の枠を覚えさせるので、書き戻した時に枠まで揃う
        val group = PersonaGroupPolicy.groupBefore(rows, insertAt) ?: source.group
        rows.add(insertAt, PersonaGroupPolicy.Row.Entry(group, source.line))
        notifyItemMoved(fromPosition, insertAt)
        return true
    }

    /** 離した合図。見た目どおりの並びと枠を、共通の一覧へ書き戻す。 */
    fun commitDrag() {
        onArrange(PersonaGroupPolicy.arrangement(rows))
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
            is PersonaGroupPolicy.Row.Entry -> bindEntry(holder as EntryHolder, row.line)
        }
    }

    private fun bindHeader(holder: HeaderHolder, group: PersonaGroupPolicy.Group) {
        holder.name.text = group.name
        holder.summary.text = PersonaGroupPolicy.summary(group)
        holder.add.setOnClickListener { onAddTo(group) }
    }

    private fun bindEntry(holder: EntryHolder, line: PersonaGroupPolicy.Line) {
        holder.enabled.setOnCheckedChangeListener(null)
        holder.enabled.isChecked = line.on
        holder.enabled.setOnCheckedChangeListener { _, checked -> onToggle(line.entry, checked) }

        holder.content.text = line.entry.body
        holder.content.alpha = if (line.on) 1f else MUTED_ALPHA
        holder.content.setOnClickListener { onEdit(line.entry) }

        holder.handle.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                onStartDrag(holder)
            }
            false
        }
        // 行は全ペルソナ共通なので、この画面から消す手段は無い。点ける/点けないだけ。
        holder.delete.visibility = View.GONE
    }

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_ENTRY = 1
        const val MUTED_ALPHA = 0.45f
    }
}

/**
 * ドラッグ＆ドロップを管理するタッチヘルパーコールバック。
 * 離した瞬間に commitDrag して、共通の一覧へ並びと枠を書き戻す。
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

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

    override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
        super.clearView(recyclerView, viewHolder)
        adapter.commitDrag()
    }
}
