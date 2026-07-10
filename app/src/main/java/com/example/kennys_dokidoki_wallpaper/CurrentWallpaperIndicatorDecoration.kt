package com.example.kennys_dokidoki_wallpaper

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.ceil

class CurrentWallpaperIndicatorDecoration(
    private val getCurrentIndex: () -> Int?,
    private val getItemCount: () -> Int
) : RecyclerView.ItemDecoration() {

    private val paintFill = Paint().apply {
        color = Color.parseColor("#FF3366") // 目立つピンク色
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val paintStroke = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }

    override fun onDrawOver(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        super.onDrawOver(c, parent, state)

        val currentIndex = getCurrentIndex()
        if (currentIndex == null || currentIndex < 0) return

        val totalCount = getItemCount()
        if (totalCount <= 0 || currentIndex >= totalCount) return

        // 以前は paddingBottom(80dp) を引いて描画範囲を狭めていましたが、
        // FastScroller(右側のスライダー)は padding を無視して画面の全高を使って動くため、ズレていました。
        // なので、親ビューの全高(parent.height)を基準にします。
        
        // スライダーのつまみ（Thumb）の縦幅の半分をマージンとして考慮し、
        // つまみの「中心」とポイントがピッタリ合うように計算します。
        val inset = 36f 
        val trackTop = inset
        val trackBottom = parent.height.toFloat() - inset
        val trackHeight = maxOf(0f, trackBottom - trackTop)

        val layoutManager = parent.layoutManager
        val ratio = if (layoutManager is GridLayoutManager) {
            // リストが3列などのグリッドの場合、個数ではなく「行(Row)」ベースで
            // スクロール位置の割合を計算した方が圧倒的に正確になります。
            val spanCount = layoutManager.spanCount
            val totalRows = ceil(totalCount.toDouble() / spanCount).toInt()
            val currentRow = currentIndex / spanCount
            
            if (totalRows <= 1) {
                0.5f
            } else {
                currentRow.toFloat() / (totalRows - 1).toFloat()
            }
        } else {
            if (totalCount <= 1) {
                0.5f
            } else {
                currentIndex.toFloat() / (totalCount - 1).toFloat()
            }
        }

        val y = trackTop + trackHeight * ratio

        // FastScrollerのトラックの少し左側、被らない位置に描画
        val x = parent.width - 24f 
        val radius = 12f

        c.drawCircle(x, y, radius, paintFill)
        c.drawCircle(x, y, radius, paintStroke)
    }
}
