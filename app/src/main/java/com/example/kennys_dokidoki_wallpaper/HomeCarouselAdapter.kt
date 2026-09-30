package com.example.kennys_dokidoki_wallpaper

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView

/** カルーセルホームの丸アイコン一覧 */
class HomeCarouselAdapter(
    private val sections: List<HomeSection>,
    private val onPick: (HomeSection, View) -> Unit
) : RecyclerView.Adapter<HomeCarouselAdapter.SectionHolder>() {

    class SectionHolder(view: View) : RecyclerView.ViewHolder(view) {
        val card: MaterialCardView = view.findViewById(R.id.card_home_section)
        val icon: ImageView = view.findViewById(R.id.iv_home_section)
        val label: TextView = view.findViewById(R.id.tv_home_section)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SectionHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_home_carousel, parent, false)
        return SectionHolder(view)
    }

    override fun getItemCount(): Int = sections.size

    override fun onBindViewHolder(holder: SectionHolder, position: Int) {
        val section = sections[position]
        holder.icon.setImageResource(section.iconRes)
        holder.label.text = section.label
        holder.card.contentDescription = section.label
        holder.card.setOnClickListener { onPick(section, holder.card) }
    }
}
