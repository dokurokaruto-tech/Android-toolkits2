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
import java.util.Collections

class PersonaInstructionsAdapter(
    val items: MutableList<PersonaItem>,
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit,
    private val onItemClick: (PersonaItem, Int) -> Unit
) : RecyclerView.Adapter<PersonaInstructionsAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvDragHandle: TextView = view.findViewById(R.id.tv_drag_handle)
        val cbEnabled: CheckBox = view.findViewById(R.id.cb_enabled)
        val tvContent: TextView = view.findViewById(R.id.tv_instruction_content)
        val btnDelete: ImageButton = view.findViewById(R.id.btn_delete_instruction)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_persona_instruction, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]

        holder.tvContent.text = item.content
        
        holder.cbEnabled.setOnCheckedChangeListener(null)
        holder.cbEnabled.isChecked = item.isEnabled

        holder.cbEnabled.setOnCheckedChangeListener { _, isChecked ->
            item.isEnabled = isChecked
        }

        // 項目をタップしたときに、詳細編集ダイアログを開くのよ☆
        holder.tvContent.setOnClickListener {
            val currentPos = holder.bindingAdapterPosition
            if (currentPos != RecyclerView.NO_POSITION) {
                onItemClick(items[currentPos], currentPos)
            }
        }

        // ドラッグハンドルを触った瞬間にドラッグを開始するのよ☆
        holder.tvDragHandle.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                onStartDrag(holder)
            }
            false
        }

        // 削除
        holder.btnDelete.setOnClickListener {
            val currentPos = holder.bindingAdapterPosition
            if (currentPos != RecyclerView.NO_POSITION) {
                items.removeAt(currentPos)
                notifyItemRemoved(currentPos)
                notifyItemRangeChanged(currentPos, items.size - currentPos)
            }
        }
    }

    override fun getItemCount() = items.size

    // ドラッグ＆ドロップ時の並べ替え処理
    fun onItemMove(fromPosition: Int, toPosition: Int): Boolean {
        if (fromPosition < toPosition) {
            for (i in fromPosition until toPosition) {
                Collections.swap(items, i, i + 1)
            }
        } else {
            for (i in fromPosition downTo toPosition + 1) {
                Collections.swap(items, i, i - 1)
            }
        }
        notifyItemMoved(fromPosition, toPosition)
        return true
    }
}

/**
 * ドラッグ＆ドロップを管理するタッチヘルパーコールバックよ☆
 */
class PersonaTouchHelperCallback(
    private val adapter: PersonaInstructionsAdapter
) : ItemTouchHelper.Callback() {

    override fun isLongPressDragEnabled(): Boolean {
        return false // タッチハンドルで即時ドラッグするので、長押しはオフ
    }

    override fun isItemViewSwipeEnabled(): Boolean {
        return false
    }

    override fun getMovementFlags(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder
    ): Int {
        val dragFlags = ItemTouchHelper.UP or ItemTouchHelper.DOWN
        val swipeFlags = 0
        return makeMovementFlags(dragFlags, swipeFlags)
    }

    override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder
    ): Boolean {
        adapter.onItemMove(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
        return true
    }

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
}
