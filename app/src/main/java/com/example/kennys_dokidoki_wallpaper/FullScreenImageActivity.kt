package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target

class FullScreenImageActivity : AppCompatActivity() {

    private lateinit var rootLayout: ConstraintLayout
    private lateinit var imageView: ImageView
    private lateinit var tvCounter: TextView
    private var albumName: String = ""
    private var currentIndex: Int = 0
    private val currentEntries = mutableListOf<ImageEntry>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_full_screen_image)

        // データを最新状態にする
        DataManager.loadData(this)

        // フルスクリーン設定
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        rootLayout = findViewById(R.id.full_screen_root)
        imageView = findViewById(R.id.full_screen_image)
        tvCounter = findViewById(R.id.tv_image_counter)
        
        albumName = intent.getStringExtra("ALBUM_NAME") ?: ""
        currentIndex = intent.getIntExtra("START_INDEX", 0)

        loadImages()
        showImage()

        // 画像の外（空白）も、左右のタップ領域として機能させるわ！
        // 戻る時はシステムの「戻る」ボタンかジェスチャーを使ってね♪
        findViewById<View>(R.id.blank_space_handler).setOnClickListener {
            // 背景クリックも左右判定に含めるようにするわよ
            // とりあえず、背景のどこを触ったかで判定するために、このハンドラー自体を
            // 左右のゾーンと同じ振る舞いに変更するわ。
        }
        
        // 画面全体のルートを触っても「戻る」んじゃなくて「めくる」ようにするわね
        rootLayout.setOnClickListener { event ->
            // ここでは座標が取れないから、下の zone_left / zone_right / blank_space_handler に任せるわ
        }

        val leftClick = View.OnClickListener {
            if (currentIndex > 0) {
                currentIndex--
            } else {
                // 最初の画像で左に行こうとしたら、最後にワープ！
                currentIndex = currentEntries.size - 1
            }
            showImage()
        }

        val rightClick = View.OnClickListener {
            if (currentIndex < currentEntries.size - 1) {
                currentIndex++
            } else {
                // 最後の画像で右に行こうとしたら、最初にワープ！
                currentIndex = 0
            }
            showImage()
        }

        // 画像の左半分をタップ
        findViewById<View>(R.id.zone_left).setOnClickListener(leftClick)
        // 画像の右半分をタップ
        findViewById<View>(R.id.zone_right).setOnClickListener(rightClick)
        
        // 背景部分もタップ領域として活用するわ！
        // activity_full_screen_image.xml の構造上、blank_space_handler が
        // 背景全体を覆っているはずだから、ここでも左右判定をするわね。
        findViewById<View>(R.id.blank_space_handler).setOnTouchListener { v, event ->
            if (event.action == android.view.MotionEvent.ACTION_UP) {
                val midX = v.width / 2
                if (event.x < midX) {
                    leftClick.onClick(v)
                } else {
                    rightClick.onClick(v)
                }
            }
            true
        }
    }

    override fun onBackPressed() {
        // 現在のインデックスを結果として返して、カルーセル側と同期させるわよ！
        val resultIntent = android.content.Intent()
        resultIntent.putExtra("FINAL_INDEX", currentIndex)
        setResult(RESULT_OK, resultIntent)
        super.onBackPressed()
    }

    private fun loadImages() {
        val virtualUris = intent.getStringArrayListExtra("VIRTUAL_ALBUM_URIS")
        if (virtualUris != null) {
            currentEntries.clear()
            virtualUris.forEach { currentEntries.add(ImageEntry(Uri.parse(it))) }
            return
        }

        val settingsPrefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val isSortAscending = settingsPrefs.getBoolean("sort_ascending", true)

        if (albumName.isNotEmpty()) {
            val set = DataManager.imageSetList.find { it.name == albumName }
            if (set != null) {
                val baseList = set.filterImages(DataManager.allImages)
                currentEntries.addAll(if (isSortAscending) baseList else baseList.reversed())
            }
        } else {
            val baseList = DataManager.allImages.toList()
            currentEntries.addAll(if (isSortAscending) baseList else baseList.reversed())
        }
    }

    private fun showImage() {
        if (currentIndex in currentEntries.indices) {
            val entry = currentEntries[currentIndex]
            
            // 枚数表示を更新するわ！
            tvCounter.text = "${currentIndex + 1} / ${currentEntries.size}"
            
            Glide.with(this)
                .load(entry.uri)
                .override(Target.SIZE_ORIGINAL)
                .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                .listener(object : RequestListener<Drawable> {
                    override fun onLoadFailed(
                        e: GlideException?,
                        model: Any?,
                        target: Target<Drawable>,
                        isFirstResource: Boolean
                    ): Boolean {
                        return false
                    }

                    override fun onResourceReady(
                        resource: Drawable,
                        model: Any,
                        target: Target<Drawable>?,
                        dataSource: DataSource,
                        isFirstResource: Boolean
                    ): Boolean {
                        val width = resource.intrinsicWidth
                        val height = resource.intrinsicHeight
                        if (width > 0 && height > 0) {
                            runOnUiThread {
                                val set = ConstraintSet()
                                set.clone(rootLayout)
                                set.setDimensionRatio(R.id.full_screen_image, "$width:$height")
                                set.applyTo(rootLayout)
                            }
                        }
                        return false
                    }
                })
                .into(imageView)
        }
    }
}
