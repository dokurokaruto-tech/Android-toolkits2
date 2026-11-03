package com.example.kennys_dokidoki_wallpaper

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import android.webkit.WebView

/** A small dark grid for previewing and selecting built-in Three.js artwork. */
class ThreeJsWallpaperGalleryActivity : AppCompatActivity() {
    private val artworks = ThreeJsArtworkCatalog.all()
    private lateinit var adapter: ArtworkAdapter
    private lateinit var artworkRecycler: RecyclerView
    private lateinit var selectionSummary: TextView
    private var selectedArtworkId = ThreeJsArtworkCatalog.DEFAULT_ARTWORK_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_threejs_wallpaper_gallery)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.threejs_gallery_root)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        selectedArtworkId = ThreeJsArtworkCatalog.selected(this).id
        selectionSummary = findViewById(R.id.tv_threejs_gallery_selection)
        artworkRecycler = findViewById(R.id.rv_threejs_artworks)
        adapter = ArtworkAdapter(artworks, selectedArtworkId, ::selectArtwork)
        artworkRecycler.layoutManager = GridLayoutManager(this, 3)
        artworkRecycler.adapter = adapter

        findViewById<View>(R.id.btn_threejs_gallery_back).setOnClickListener { finish() }
        findViewById<MaterialButton>(R.id.btn_threejs_gallery_apply).setOnClickListener {
            val artwork = ThreeJsArtworkCatalog.find(selectedArtworkId)
            ThreeJsArtworkCatalog.selectAsWallpaper(this, artwork)
            Toast.makeText(this, "${artwork.title} を選択しました", Toast.LENGTH_SHORT).show()
            val wallpaperIntent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
                putExtra(
                    WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                    ComponentName(this@ThreeJsWallpaperGalleryActivity, MyWallpaperService::class.java)
                )
            }
            startActivity(wallpaperIntent)
            finish()
        }
        updateSelectionSummary()
    }

    override fun onDestroy() {
        if (::artworkRecycler.isInitialized) artworkRecycler.adapter = null
        super.onDestroy()
    }

    private fun selectArtwork(id: String) {
        if (selectedArtworkId == id) return
        selectedArtworkId = id
        adapter.setSelectedArtwork(id)
        updateSelectionSummary()
    }

    private fun updateSelectionSummary() {
        val artwork = ThreeJsArtworkCatalog.find(selectedArtworkId)
        selectionSummary.text = "選択中  ·  ${artwork.caption}"
    }

    private class ArtworkAdapter(
        private val items: List<ThreeJsArtwork>,
        private var selectedArtworkId: String,
        private val onSelect: (String) -> Unit
    ) : RecyclerView.Adapter<ArtworkHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ArtworkHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_threejs_artwork, parent, false)
            return ArtworkHolder(view, onSelect)
        }

        override fun onBindViewHolder(holder: ArtworkHolder, position: Int) {
            val artwork = items[position]
            holder.bind(artwork, artwork.id == selectedArtworkId)
        }

        override fun getItemCount(): Int = items.size

        fun setSelectedArtwork(id: String) {
            val oldPosition = items.indexOfFirst { it.id == selectedArtworkId }
            val newPosition = items.indexOfFirst { it.id == id }
            selectedArtworkId = id
            if (oldPosition >= 0) notifyItemChanged(oldPosition)
            if (newPosition >= 0 && newPosition != oldPosition) notifyItemChanged(newPosition)
        }

        override fun onViewRecycled(holder: ArtworkHolder) {
            holder.releasePreview()
            super.onViewRecycled(holder)
        }

        override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
            for (index in 0 until recyclerView.childCount) {
                val holder = recyclerView.getChildViewHolder(recyclerView.getChildAt(index)) as? ArtworkHolder
                holder?.releasePreview()
            }
            super.onDetachedFromRecyclerView(recyclerView)
        }
    }

    private class ArtworkHolder(
        itemView: View,
        private val onSelect: (String) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        private val card: MaterialCardView = itemView.findViewById(R.id.card_threejs_artwork)
        private val previewContainer: FrameLayout = itemView.findViewById(R.id.threejs_artwork_preview_container)
        private val clickOverlay: View = itemView.findViewById(R.id.threejs_artwork_click_overlay)
        private val caption: TextView = itemView.findViewById(R.id.threejs_artwork_caption)
        private val title: TextView = itemView.findViewById(R.id.threejs_artwork_title)
        private val selectedBadge: TextView = itemView.findViewById(R.id.threejs_artwork_selected_badge)
        private var previewArtworkId: String? = null
        private var previewWebView: WebView? = null

        fun bind(artwork: ThreeJsArtwork, selected: Boolean) {
            caption.text = artwork.caption
            title.text = artwork.title
            card.contentDescription = if (selected) "${artwork.title}、選択中" else artwork.title
            selectedBadge.visibility = if (selected) View.VISIBLE else View.GONE
            card.strokeColor = if (selected) 0xFFD0BCFF.toInt() else 0xFF242424.toInt()
            card.strokeWidth = ((if (selected) 2f else 1f) * itemView.resources.displayMetrics.density).toInt()

            val click = View.OnClickListener { onSelect(artwork.id) }
            card.setOnClickListener(click)
            clickOverlay.setOnClickListener(click)

            if (previewArtworkId != artwork.id || previewWebView == null) {
                loadPreview(artwork)
            }
        }

        private fun loadPreview(artwork: ThreeJsArtwork) {
            releasePreview()
            val webView = ThreeJsWebViewFactory.create(itemView.context, artwork, previewMode = true).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                isFocusable = false
                isFocusableInTouchMode = false
                isClickable = false
                isLongClickable = false
            }
            previewContainer.addView(webView, 0)
            previewWebView = webView
            previewArtworkId = artwork.id
            webView.loadUrl(ThreeJsArtworkCatalog.ENTRY_PAGE)
        }

        fun releasePreview() {
            val webView = previewWebView ?: return
            previewWebView = null
            previewArtworkId = null
            try {
                webView.stopLoading()
                (webView.parent as? ViewGroup)?.removeView(webView)
                webView.removeJavascriptInterface(ThreeJsWebViewFactory.BRIDGE_NAME)
                webView.destroy()
            } catch (_: Exception) {
                // A recycled preview should not affect the gallery or selected artwork.
            }
        }
    }
}
