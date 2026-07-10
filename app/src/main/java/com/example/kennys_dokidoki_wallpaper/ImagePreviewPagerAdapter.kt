package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.util.DisplayMetrics
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import androidx.cardview.widget.CardView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import java.security.MessageDigest

class ImagePreviewPagerAdapter(
    private val entries: List<ImageEntry>,
    private val context: Context,
    private val onImageClick: (Int) -> Unit // Added callback
) : RecyclerView.Adapter<ImagePreviewPagerAdapter.ViewHolder>() {

    private val screenRatio: String

    init {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        screenRatio = "${metrics.widthPixels}:${metrics.heightPixels}"
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val imageView: ImageView = view.findViewById(R.id.preview_image)
        val cardView: CardView = view.findViewById(R.id.card_view)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(context).inflate(R.layout.item_preview_card, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = entries[position]
        
        val layoutParams = holder.cardView.layoutParams as ConstraintLayout.LayoutParams
        layoutParams.dimensionRatio = screenRatio
        holder.cardView.layoutParams = layoutParams

        val glideRequest = Glide.with(context)
            .load(entry.uri)
            .diskCacheStrategy(DiskCacheStrategy.ALL)

        if (entry.cropRect != null) {
            glideRequest.transform(CropTransformation(entry.cropRect!!))
        } else {
            glideRequest.centerCrop()
        }

        glideRequest.into(holder.imageView)
        
        // Handle click on image
        holder.cardView.setOnClickListener {
            onImageClick(position)
        }
    }

    override fun getItemCount() = entries.size

    class CropTransformation(private val cropRect: android.graphics.RectF) : BitmapTransformation() {
        override fun transform(pool: BitmapPool, toTransform: Bitmap, outWidth: Int, outHeight: Int): Bitmap {
            val left = (toTransform.width * cropRect.left).toInt()
            val top = (toTransform.height * cropRect.top).toInt()
            val right = (toTransform.width * cropRect.right).toInt()
            val bottom = (toTransform.height * cropRect.bottom).toInt()
            
            val width = (right - left).coerceAtLeast(1)
            val height = (bottom - top).coerceAtLeast(1)
            
            return Bitmap.createBitmap(toTransform, left, top, width, height)
        }

        override fun updateDiskCacheKey(messageDigest: MessageDigest) {
            messageDigest.update("crop_transform_${cropRect.left}_${cropRect.top}_${cropRect.right}_${cropRect.bottom}".toByteArray())
        }
    }
}
