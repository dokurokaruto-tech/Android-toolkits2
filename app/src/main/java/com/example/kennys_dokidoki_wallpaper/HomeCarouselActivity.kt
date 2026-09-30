package com.example.kennys_dokidoki_wallpaper

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityOptionsCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView

/**
 * アプリの入口。機能ごとの丸アイコンをカルーセルで見せ、選んだ画面だけを開く。
 * 中央のアイコンが選択中で、左右スワイプで隣の機能へ移る。
 */
class HomeCarouselActivity : AppCompatActivity() {

    private lateinit var carousel: RecyclerView
    private lateinit var dots: LinearLayout

    private val snapHelper = PagerSnapHelper()
    private val sections = HomeSection.entries

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_home_carousel)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.home_carousel_root)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        carousel = findViewById(R.id.recycler_home_carousel)
        dots = findViewById(R.id.home_carousel_dots)

        setupCarousel()
        setupDots()
    }

    private fun setupCarousel() {
        carousel.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        carousel.adapter = HomeCarouselAdapter(sections) { section, view -> openSection(section, view) }
        snapHelper.attachToRecyclerView(carousel)

        // 先頭のアイコンを画面中央に置くため、アイコン1つ分の余白を左右に入れる
        carousel.post {
            val itemWidth = resources.getDimensionPixelSize(R.dimen.home_carousel_icon_size)
            val padding = HomeCarouselPolicy.sidePadding(carousel.width, itemWidth)
            carousel.setPadding(padding, 0, padding, 0)
            applyCarouselTransform()
        }

        carousel.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                applyCarouselTransform()
            }
        })
    }

    /** スクロールに合わせて中央のアイコンを大きく、隣を小さく薄くする */
    private fun applyCarouselTransform() {
        val listCenter = carousel.width / 2f
        val itemSpan = resources.getDimensionPixelSize(R.dimen.home_carousel_icon_size).toFloat()
        for (index in 0 until carousel.childCount) {
            val child = carousel.getChildAt(index)
            val childCenter = (child.left + child.right) / 2f
            val ratio = HomeCarouselPolicy.offsetRatio(childCenter, listCenter, itemSpan)
            val scale = HomeCarouselPolicy.scale(ratio)
            child.scaleX = scale
            child.scaleY = scale
            child.alpha = HomeCarouselPolicy.alpha(ratio)
        }
        updateDots(currentPosition())
    }

    private fun currentPosition(): Int {
        val manager = carousel.layoutManager ?: return 0
        val snapped = snapHelper.findSnapView(manager) ?: return 0
        return manager.getPosition(snapped)
    }

    private fun setupDots() {
        val size = resources.getDimensionPixelSize(R.dimen.home_carousel_dot_size)
        val gap = size / 2
        sections.forEach { _ ->
            val dot = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    marginStart = gap
                    marginEnd = gap
                }
                setBackgroundResource(R.drawable.home_carousel_dot)
            }
            dots.addView(dot)
        }
        updateDots(0)
    }

    /** 選択中のページだけ大きく表示する。移動もアニメーションで繋ぐ */
    private fun updateDots(position: Int) {
        for (index in 0 until dots.childCount) {
            val dot = dots.getChildAt(index)
            val selected = index == position
            dot.animate()
                .alpha(if (selected) 1f else HomeCarouselPolicy.alpha(1f))
                .scaleX(if (selected) 1.6f else 1f)
                .scaleY(if (selected) 1.6f else 1f)
                .setDuration(DOT_ANIM_MS)
                .start()
        }
    }

    /** アイコンを押し込むアニメーションのあと、その機能の画面へ拡大遷移する */
    private fun openSection(section: HomeSection, source: View) {
        source.animate()
            .scaleX(PRESS_SCALE)
            .scaleY(PRESS_SCALE)
            .setDuration(PRESS_ANIM_MS)
            .withEndAction {
                source.animate().scaleX(1f).scaleY(1f).setDuration(PRESS_ANIM_MS).start()
                launchSection(section, source)
            }
            .start()
    }

    private fun launchSection(section: HomeSection, source: View) {
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra(HomeSection.EXTRA_KEY, section.name)
        }
        val options = ActivityOptionsCompat.makeScaleUpAnimation(
            source, 0, 0, source.width, source.height
        )
        startActivity(intent, options.toBundle())
    }

    override fun onResume() {
        super.onResume()
        carousel.post { applyCarouselTransform() }
    }

    private companion object {
        const val PRESS_SCALE = 0.92f
        const val PRESS_ANIM_MS = 90L
        const val DOT_ANIM_MS = 160L
    }
}
