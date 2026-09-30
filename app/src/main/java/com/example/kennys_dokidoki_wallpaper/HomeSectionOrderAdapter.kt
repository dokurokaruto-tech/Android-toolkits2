package com.example.kennys_dokidoki_wallpaper

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView

/** ホーム設定ポップアップの並べ替えリスト。ドラッグでも上下ボタンでも動かせる */
class HomeSectionOrderAdapter(
    private var order: List<HomeSection>,
    private val onReordered: (List<HomeSection>) -> Unit
) : RecyclerView.Adapter<HomeSectionOrderAdapter.OrderHolder>() {

    private var dragStarter: ((RecyclerView.ViewHolder) -> Unit)? = null

    class OrderHolder(view: View) : RecyclerView.ViewHolder(view) {
        val iconCard: MaterialCardView = view.findViewById(R.id.card_order_icon)
        val icon: ImageView = view.findViewById(R.id.iv_order_icon)
        val label: TextView = view.findViewById(R.id.tv_order_label)
        val up: ImageButton = view.findViewById(R.id.btn_order_up)
        val down: ImageButton = view.findViewById(R.id.btn_order_down)
        val handle: ImageView = view.findViewById(R.id.iv_order_handle)
    }

    fun attachDrag(recycler: RecyclerView) {
        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                move(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun isLongPressDragEnabled(): Boolean = false
        }
        val helper = ItemTouchHelper(callback)
        helper.attachToRecyclerView(recycler)
        dragStarter = { holder -> helper.startDrag(holder) }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): OrderHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_home_section_order, parent, false)
        return OrderHolder(view)
    }

    override fun getItemCount(): Int = order.size

    @SuppressLint("ClickableViewAccessibility")
    override fun onBindViewHolder(holder: OrderHolder, position: Int) {
        val section = order[position]
        val context = holder.itemView.context

        holder.label.text = section.label
        holder.icon.setImageResource(section.iconRes)
        holder.icon.imageTintList =
            ColorStateList.valueOf(ContextCompat.getColor(context, section.iconColorRes))
        holder.iconCard.setCardBackgroundColor(
            ContextCompat.getColor(context, section.containerColorRes)
        )

        holder.up.setOnClickListener {
            move(holder.bindingAdapterPosition, holder.bindingAdapterPosition - 1)
        }
        holder.down.setOnClickListener {
            move(holder.bindingAdapterPosition, holder.bindingAdapterPosition + 1)
        }
        holder.handle.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                dragStarter?.invoke(holder)
            }
            false
        }
    }

    private fun move(from: Int, to: Int) {
        val moved = HomeSectionOrder.move(order, from, to)
        if (moved === order) {
            return
        }
        order = moved
        notifyItemMoved(from, to)
        onReordered(order)
    }
}
