package com.example.kennys_dokidoki_wallpaper

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class UserPersonaAdapter(
    private val personas: List<UserPersona>,
    private val activePersonaId: String?,
    private val onPersonaClick: (UserPersona) -> Unit,
    private val onMenuClick: (UserPersona, View) -> Unit
) : RecyclerView.Adapter<UserPersonaAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tv_persona_name)
        val tvDescription: TextView = view.findViewById(R.id.tv_persona_description)
        val tvActiveIndicator: TextView = view.findViewById(R.id.tv_active_indicator)
        val btnMenu: ImageButton = view.findViewById(R.id.btn_persona_menu)
        val rootItem: View = view
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_user_persona, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val persona = personas[position]
        val isActive = persona.id == activePersonaId

        holder.tvName.text = persona.name
        holder.tvDescription.text = if (persona.mergedPrompt.isNotBlank()) {
            persona.mergedPrompt
        } else {
            "（設定なし）"
        }

        // アクティブ状態の表示
        if (isActive) {
            holder.tvActiveIndicator.visibility = View.VISIBLE
            holder.rootItem.setBackgroundResource(R.drawable.bg_persona_item_active)
        } else {
            holder.tvActiveIndicator.visibility = View.GONE
            holder.rootItem.setBackgroundResource(R.drawable.bg_persona_item)
        }

        // タップで切り替え
        holder.rootItem.setOnClickListener { onPersonaClick(persona) }

        // メニューボタン（ケバブアイコン）
        holder.btnMenu.setOnClickListener { view ->
            onMenuClick(persona, view)
        }
    }

    override fun getItemCount() = personas.size
}
