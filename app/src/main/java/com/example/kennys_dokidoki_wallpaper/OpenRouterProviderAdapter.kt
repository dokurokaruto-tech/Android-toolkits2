package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import java.math.BigDecimal
import java.util.Locale

internal class OpenRouterProviderAdapter(
    private val endpoints: List<OpenRouterEndpoint>,
    private val selectedTag: String?,
    private val onSelect: (OpenRouterEndpoint) -> Unit
) : RecyclerView.Adapter<OpenRouterProviderAdapter.Holder>() {
    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val card = view as MaterialCardView
        val name: TextView = view.findViewById(R.id.tv_endpoint_name)
        val tag: TextView = view.findViewById(R.id.tv_endpoint_tag)
        val price: TextView = view.findViewById(R.id.tv_endpoint_price)
        val health: TextView = view.findViewById(R.id.tv_endpoint_health)
        val speed: TextView = view.findViewById(R.id.tv_endpoint_speed)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_openrouter_provider, parent, false))

    override fun getItemCount(): Int = endpoints.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val endpoint = endpoints[position]
        val context = holder.itemView.context
        val active = endpoint.tag != null && endpoint.tag == selectedTag
        holder.name.text = endpoint.name
        holder.tag.text = listOfNotNull(
            endpoint.tag ?: context.getString(R.string.or_provider_no_tag),
            context.getString(R.string.or_provider_active).takeIf { active }
        ).joinToString(" · ")
        holder.price.text = prices(context, endpoint)
        holder.health.text = context.getString(
            R.string.or_provider_uptime,
            percent(context, endpoint.uptime5m),
            percent(context, endpoint.uptime30m),
            percent(context, endpoint.uptime1d)
        )
        holder.speed.text = context.getString(
            R.string.or_provider_speed,
            metric(context, endpoint.latency, R.string.or_provider_latency),
            metric(context, endpoint.throughput, R.string.or_provider_throughput)
        )
        val background = if (active) {
            com.google.android.material.R.attr.colorSecondaryContainer
        } else {
            com.google.android.material.R.attr.colorSurface
        }
        holder.card.setCardBackgroundColor(MaterialColors.getColor(holder.card, background))
        holder.card.isSelected = active
        holder.card.setOnClickListener { onSelect(endpoint) }
        holder.card.isEnabled = endpoint.tag != null
        holder.card.isClickable = endpoint.tag != null
    }

    private fun prices(context: Context, endpoint: OpenRouterEndpoint): String = buildList {
        add(context.getString(
            R.string.or_provider_prices,
            money(context, endpoint.promptPrice), money(context, endpoint.completionPrice)
        ))
        endpoint.cacheReadPrice?.let {
            add(context.getString(R.string.or_provider_cache_read, money(context, it)))
        }
        endpoint.cacheWritePrice?.let {
            add(context.getString(R.string.or_provider_cache_write, money(context, it)))
        }
        endpoint.requestPrice?.takeIf { it.signum() > 0 }?.let {
            add(context.getString(R.string.or_provider_request, it.stripTrailingZeros().toPlainString()))
        }
    }.joinToString("\n")

    private fun money(context: Context, price: BigDecimal?): String =
        price?.let { "$${OpenRouterEndpoints.perMillion(it)}" }
            ?: context.getString(R.string.or_provider_unknown)

    private fun percent(context: Context, value: Double?): String =
        value?.let { String.format(Locale.US, "%.2f%%", it) }
            ?: context.getString(R.string.or_provider_unknown)

    private fun metric(context: Context, value: Double?, unit: Int): String =
        value?.let { context.getString(unit, String.format(Locale.US, "%.2f", it)) }
            ?: context.getString(R.string.or_provider_unknown)
}
