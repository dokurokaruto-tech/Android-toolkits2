package com.example.kennys_dokidoki_wallpaper

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityOptionsCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton

/**
 * アプリの入口。機能ごとの丸アイコンをカルーセルで見せ、選んだ画面だけを開く。
 * 端はつながっていて、先頭「全画像」の左には末尾の機能が現れる。
 */
class HomeCarouselActivity : AppCompatActivity() {

    private lateinit var carousel: RecyclerView
    private lateinit var dots: LinearLayout
    private lateinit var live2dStage: BocchiLive2dStageView

    private val snapHelper = PagerSnapHelper()
    private val carouselAdapter = HomeCarouselAdapter { section, view -> openSection(section, view) }
    private var sections: List<HomeSection> = emptyList()
    private var activeSectionIndex = -1

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
        live2dStage = findViewById(R.id.live2d_bocchi_stage)
        findViewById<ImageButton>(R.id.btn_home_settings).setOnClickListener { showHomeSettings() }

        setupCarousel()
        applyOrder(HomeSectionOrder.load(this))
    }

    private fun setupCarousel() {
        carousel.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        carousel.adapter = carouselAdapter
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
                live2dStage.onCarouselScroll(dx.toFloat(), recyclerView.width.toFloat())
            }
        })
    }

    /** 並びを反映し、先頭の機能を中央へ戻す */
    private fun applyOrder(order: List<HomeSection>) {
        sections = order
        carouselAdapter.submit(order)
        setupDots()
        // 左右の余白が付いたあとに中央へ寄せたいので、レイアウト後に走らせる
        carousel.post {
            layoutManager()?.scrollToPositionWithOffset(
                HomeCarouselPolicy.startPosition(order.size), 0
            )
            applyCarouselTransform()
        }
    }

    private fun layoutManager(): LinearLayoutManager? = carousel.layoutManager as? LinearLayoutManager

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
        val sectionIdx = currentSectionIndex()
        updateDots(sectionIdx)
        if (sectionIdx != activeSectionIndex && sections.isNotEmpty()) {
            activeSectionIndex = sectionIdx
            live2dStage.onSectionSelect(sections[sectionIdx])
        }
    }

    private fun currentSectionIndex(): Int {
        val manager = carousel.layoutManager ?: return 0
        val snapped = snapHelper.findSnapView(manager) ?: return 0
        return HomeCarouselPolicy.sectionIndex(manager.getPosition(snapped), sections.size)
    }

    private fun setupDots() {
        dots.removeAllViews()
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
        val options = ActivityOptionsCompat.makeScaleUpAnimation(
            source, 0, 0, source.width, source.height
        )
        startActivity(intentFor(section), options.toBundle())
    }

    /** 専用画面を持つ機能はその Activity へ、それ以外は MainActivity の該当画面へ */
    private fun intentFor(section: HomeSection): Intent = when (section) {
        HomeSection.DIARY -> Intent(this, DiaryCalendarActivity::class.java)
        else -> Intent(this, MainActivity::class.java)
            .putExtra(HomeSection.EXTRA_KEY, section.name)
    }

    /** 右上の設定。いまは並び順の入れ替えだけを扱う */
    private fun showHomeSettings() {
        val (_, view) = Md3PopupDialog.inflate(this, R.layout.dialog_home_carousel_settings)
        val recycler = view.findViewById<RecyclerView>(R.id.recycler_home_section_order)
        val adapter = HomeSectionOrderAdapter(sections) { reordered ->
            HomeSectionOrder.save(this, reordered)
            applyOrder(reordered)
        }
        recycler.layoutManager = LinearLayoutManager(view.context)
        recycler.adapter = adapter
        adapter.attachDrag(recycler)

        val dialog = Md3PopupDialog.show(this, view)
        view.findViewById<MaterialButton>(R.id.btn_home_settings_close).setOnClickListener {
            dialog.dismiss()
        }
    }

    override fun onResume() {
        super.onResume()
        live2dStage.startStage()
        carousel.post { applyCarouselTransform() }
    }

    override fun onPause() {
        live2dStage.stopStage()
        super.onPause()
    }

    private companion object {
        const val PRESS_SCALE = 0.92f
        const val PRESS_ANIM_MS = 90L
        const val DOT_ANIM_MS = 160L
    }
}
