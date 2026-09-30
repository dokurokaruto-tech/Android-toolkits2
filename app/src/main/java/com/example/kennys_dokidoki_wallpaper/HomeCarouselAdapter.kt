package com.example.kennys_dokidoki_wallpaper

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView

/**
 * カルーセルホームの丸アイコン一覧。
 * 同じ並びを何周も返すことで、最後のアイコンの右に先頭が続く循環UIにする。
 */
class HomeCarouselAdapter(
    private val onPick: (HomeSection, View) -> Unit
) : RecyclerView.Adapter<HomeCarouselAdapter.SectionHolder>() {

    private var sections: List<HomeSection> = emptyList()

    class SectionHolder(view: View) : RecyclerView.ViewHolder(view) {
        val card: MaterialCardView = view.findViewById(R.id.card_home_section)
        val icon: ImageView = view.findViewById(R.id.iv_home_section)
        val label: TextView = view.findViewById(R.id.tv_home_section)
    }

    @SuppressLint("NotifyDataSetChanged")
    fun submit(order: List<HomeSection>) {
        sections = order
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SectionHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_home_carousel, parent, false)
        return SectionHolder(view)
    }

    override fun getItemCount(): Int = HomeCarouselPolicy.loopCount(sections.size)

    override fun onBindViewHolder(holder: SectionHolder, position: Int) {
        val section = sections[HomeCarouselPolicy.sectionIndex(position, sections.size)]
        val context = holder.itemView.context

        holder.icon.setImageResource(section.iconRes)
        holder.icon.imageTintList =
            ColorStateList.valueOf(ContextCompat.getColor(context, section.iconColorRes))
        holder.card.setCardBackgroundColor(
            ContextCompat.getColor(context, section.containerColorRes)
        )
        holder.label.text = section.label
        holder.card.contentDescription = section.label
        holder.card.setOnClickListener { onPick(section, holder.card) }
    }
}
