package com.example.kennys_dokidoki_wallpaper

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors

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

        holder.tvActiveIndicator.visibility = if (isActive) View.VISIBLE else View.GONE
        holder.rootItem.isSelected = isActive
        val card = holder.rootItem as MaterialCardView
        val background = if (isActive) {
            com.google.android.material.R.attr.colorSecondaryContainer
        } else {
            com.google.android.material.R.attr.colorSurfaceVariant
        }
        card.setCardBackgroundColor(MaterialColors.getColor(card, background))

        // タップで切り替え
        holder.rootItem.setOnClickListener { onPersonaClick(persona) }

        // メニューボタン（ケバブアイコン）
        holder.btnMenu.setOnClickListener { view ->
            onMenuClick(persona, view)
        }
    }

    override fun getItemCount() = personas.size
}
