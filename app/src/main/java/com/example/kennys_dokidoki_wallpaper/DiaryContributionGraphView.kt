package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import java.time.LocalDate
import kotlin.math.min

/** 画面上端に置く小さな7段ヒートマップ。詳細日付の選択は別の拡大UIで行う。 */
internal class DiaryContributionGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var weeks: List<List<LocalDate>> = emptyList()
    private var entryLengths: Map<String, Int> = emptyMap()
    private var palette = DiaryPalette(0xFF000000.toInt(), 0xFFFF2020.toInt())
    private var highlightedToday: LocalDate? = null

    fun show(
        weeks: List<List<LocalDate>>,
        entryLengths: Map<String, Int>,
        palette: DiaryPalette,
        today: LocalDate? = null
    ) {
        this.weeks = weeks
        this.entryLengths = entryLengths
        this.palette = palette
        this.highlightedToday = today
        val todayLabel = today?.let {
            " 今日: ${DiaryStore.dateKey(it.year, it.monthValue, it.dayOfMonth)}"
        }.orEmpty()
        contentDescription = "日記の記録グラフ。過去${weeks.size}週間。$todayLabel タップすると日付一覧を開きます。"
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val cell = dp(CELL_DP).toInt()
        val gap = dp(GAP_DP).toInt()
        val desiredHeight = paddingTop + paddingBottom + cell * 7 + gap * 6
        val desiredWidth = paddingStart + paddingEnd +
            weeks.size * cell + (weeks.size - 1).coerceAtLeast(0) * gap
        setMeasuredDimension(
            resolveSize(desiredWidth, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (weeks.isEmpty()) return

        val gap = dp(GAP_DP)
        val preferredCell = dp(CELL_DP)
        val availableWidth = (width - paddingStart - paddingEnd).toFloat().coerceAtLeast(0f)
        val cell = min(preferredCell, (availableWidth - gap * (weeks.size - 1)) / weeks.size)
            .coerceAtLeast(dp(MIN_CELL_DP))
        val totalWidth = weeks.size * cell + (weeks.size - 1) * gap
        val startX = paddingStart + ((availableWidth - totalWidth) / 2f).coerceAtLeast(0f)
        val startY = paddingTop.toFloat()

        weeks.forEachIndexed { weekIndex, week ->
            week.forEachIndexed { dayIndex, date ->
                val key = DiaryStore.dateKey(date.year, date.monthValue, date.dayOfMonth)
                val count = entryLengths[key] ?: 0
                val level = DiaryContributionPolicy.level(count, entryLengths.containsKey(key))
                val left = startX + weekIndex * (cell + gap)
                val top = startY + dayIndex * (cell + gap)
                paint.style = Paint.Style.FILL
                paint.color = palette.cellColor(level)
                canvas.drawRect(left, top, left + cell, top + cell, paint)
                if (date == highlightedToday) {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = min(dp(2f), cell * 0.3f)
                    paint.color = palette.text
                    canvas.drawRect(left, top, left + cell, top + cell, paint)
                    paint.style = Paint.Style.FILL
                }
            }
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private companion object {
        const val CELL_DP = 8f
        const val GAP_DP = 2f
        const val MIN_CELL_DP = 2f
    }
}
