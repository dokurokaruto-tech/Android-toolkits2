package com.example.kennys_dokidoki_wallpaper

import android.graphics.drawable.Drawable
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target

/**
 * サムネイル読み込みの共通形。
 * 失敗したら表示位置にピンクの「!」を出す。真っ暗なままで失敗を隠さない。
 */
fun ImageView.loadThumb(model: Any?, width: Int, height: Int, strategy: DiskCacheStrategy) {
    scaleType = ImageView.ScaleType.CENTER_CROP
    Glide.with(this)
        .load(model)
        .override(width, height)
        .diskCacheStrategy(strategy)
        .error(R.drawable.thumb_broken)
        .listener(object : RequestListener<Drawable> {
            override fun onLoadFailed(
                error: GlideException?,
                model: Any?,
                target: Target<Drawable>,
                isFirstResource: Boolean
            ): Boolean {
                // error drawable は等倍で中央に出す。
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                return false
            }

            override fun onResourceReady(
                resource: Drawable,
                model: Any,
                target: Target<Drawable>,
                dataSource: DataSource,
                isFirstResource: Boolean
            ): Boolean {
                scaleType = ImageView.ScaleType.CENTER_CROP
                return false
            }
        })
        .centerCrop()
        .into(this)
}
