package com.example.kennys_dokidoki_wallpaper

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 生成中プレビューをアプリ側で大きく見せる。
 * 戻ると、下に積んだその日の閲覧フォルダへ戻る。
 */
class GenerationLivePreviewActivity : AppCompatActivity() {

    private lateinit var imageView: ImageView
    private lateinit var percentView: TextView
    private lateinit var batchView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_generation_live_preview)
        imageView = findViewById(R.id.iv_live_generation)
        percentView = findViewById(R.id.tv_live_percent)
        batchView = findViewById(R.id.tv_live_batch)
        observeProgress()
    }

    private fun observeProgress() {
        lifecycleScope.launch {
            GenerationProgressManager.state.collectLatest { state ->
                if (state.currentImage != null) {
                    imageView.setImageBitmap(state.currentImage)
                }
                percentView.text = "${(state.progress * 100).toInt()}%"
                if (state.totalBatch > 1) {
                    batchView.visibility = View.VISIBLE
                    batchView.text = "${state.currentBatch}/${state.totalBatch}"
                } else {
                    batchView.visibility = View.GONE
                }
            }
        }
    }

    override fun onBackPressed() {
        finish()
        overridePendingTransition(0, 0)
    }
}
