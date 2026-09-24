package com.example.kennys_dokidoki_wallpaper

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textview.MaterialTextView

/** 1行ぶん。本文と、その下に並ぶ短い操作ボタンだけ持つ。 */
data class PersonaActionRow(
    val id: String,
    val body: String,
    val note: String = "",
    val actions: List<String> = emptyList(),
    val onTap: (() -> Unit)? = null
)

/**
 * カテゴリー管理とプール管理で同じ行を使うための軽いアダプター。
 * ボタン押下は (行, ラベル) で上に戻すので、ここで状態を持たない。
 */
class PersonaActionRowAdapter(
    private val onAction: (PersonaActionRow, String) -> Unit
) : RecyclerView.Adapter<PersonaActionRowAdapter.Holder>() {

    private val rows = mutableListOf<PersonaActionRow>()

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val card: MaterialCardView = view.findViewById(R.id.card_persona_row)
        val body: MaterialTextView = view.findViewById(R.id.tv_persona_row_body)
        val note: MaterialTextView = view.findViewById(R.id.tv_persona_row_note)
        val actions: LinearLayout = view.findViewById(R.id.ll_persona_row_actions)
    }

    fun submit(next: List<PersonaActionRow>) {
        rows.clear()
        rows.addAll(next)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_persona_action_row, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = rows[position]
        val context = holder.actions.context

        holder.body.text = row.body
        holder.note.text = row.note
        holder.note.visibility = if (row.note.isEmpty()) View.GONE else View.VISIBLE

        holder.actions.removeAllViews()
        row.actions.forEach { label ->
            val button = LayoutInflater.from(context)
                .inflate(R.layout.item_persona_row_action, holder.actions, false) as MaterialButton
            button.text = label
            button.setOnClickListener { onAction(row, label) }
            holder.actions.addView(button)
        }

        val tap = row.onTap
        holder.card.isClickable = tap != null
        holder.card.setOnClickListener { tap?.invoke() }
    }

    override fun getItemCount(): Int = rows.size
}
