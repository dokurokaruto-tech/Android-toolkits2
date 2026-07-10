package com.example.kennys_dokidoki_wallpaper

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 錬成中の進捗をリアルタイムで表示し、PiP（Picture-in-Picture）にも対応した画面よ！
 * YouTubeみたいにトントンって叩いたら大きくなるし、ボタンも付いてるわ♪
 */
class GenerationProgressActivity : AppCompatActivity() {

    private lateinit var ivProgress: ImageView
    private lateinit var pbGeneration: ProgressBar
    private lateinit var tvPercent: TextView
    private lateinit var tvBatchCount: TextView
    private lateinit var llControls: View
    private lateinit var gestureDetector: GestureDetector

    companion object {
        const val ACTION_STOP = "com.example.ACTION_STOP_GEN"
        const val ACTION_SKIP = "com.example.ACTION_SKIP_GEN"
        const val ACTION_MAXIMIZE = "com.example.ACTION_MAXIMIZE"
        var isPipActive = false
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_STOP -> GenerationProgressManager.shouldInterrupt = true
                ACTION_SKIP -> GenerationProgressManager.shouldSkip = true
                ACTION_MAXIMIZE -> {
                    // PiPから戻る時は、Activityを通常表示にするわ
                    val startIntent = Intent(this@GenerationProgressActivity, GenerationProgressActivity::class.java)
                    startIntent.flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    startActivity(startIntent)
                }
            }
            updatePiPParams() // ボタンの状態とか変わるかもしれないから更新しとくわ
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isPipActive = true
        setContentView(R.layout.activity_generation_progress)

        ivProgress = findViewById(R.id.iv_progress_preview)
        pbGeneration = findViewById(R.id.pb_generation)
        tvPercent = findViewById(R.id.tv_progress_percent)
        tvBatchCount = findViewById(R.id.tv_batch_count)
        llControls = findViewById(R.id.ll_controls)

        setupButtons()
        setupGestures()
        observeProgress()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            registerReceiver(receiver, IntentFilter().apply {
                addAction(ACTION_STOP)
                addAction(ACTION_SKIP)
                addAction(ACTION_MAXIMIZE)
            }, RECEIVER_NOT_EXPORTED)
        }
        
        // 開始したらすぐにPiPに入るわよ！
        ivProgress.post {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val params = PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(9, 16))
                    .build()
                enterPictureInPictureMode(params)
            }
        }
    }

    private fun setupButtons() {
        findViewById<ImageButton>(R.id.btn_pip_stop).setOnClickListener {
            GenerationProgressManager.shouldInterrupt = true
        }
        findViewById<ImageButton>(R.id.btn_pip_skip).setOnClickListener {
            GenerationProgressManager.shouldSkip = true
        }
        findViewById<ImageButton>(R.id.btn_pip_maximize).setOnClickListener {
            // 全画面からPiPに戻りたいってことね！了解よ！っ！
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val params = PictureInPictureParams.Builder()
                    .setAspectRatio(if (isLargePiP) Rational(1, 1) else Rational(9, 16))
                    .build()
                enterPictureInPictureMode(params)
            }
        }
    }

    private fun setupGestures() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                // YouTubeみたいにダブルタップでPiPのサイズを切り替えたいわよね！
                // システムのPiPサイズを直接変えるAPIは無いけど、アスペクト比を変えることで
                // 見かけ上のサイズ感を調整することはできるわよ
                togglePiPSize()
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                // タップでコントロールを表示/非表示にするわ（PiP中は標準のRemoteActionが出るけど）
                llControls.visibility = if (llControls.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                return true
            }
        })

        findViewById<View>(R.id.pip_root).setOnTouchListener { v, event ->
            gestureDetector.onTouchEvent(event)
            true
        }
    }

    private var isLargePiP = false
    private fun togglePiPSize() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            isLargePiP = !isLargePiP
            val ratio = if (isLargePiP) Rational(1, 1) else Rational(9, 16)
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(ratio)
                .build()
            setPictureInPictureParams(params)
        }
    }

    private fun observeProgress() {
        lifecycleScope.launch {
            GenerationProgressManager.state.collectLatest { state ->
                if (!state.isGenerating) {
                    // 生成が終わったらこの画面も閉じるわ！
                    // PiPモード中でも関係なく、お仕事終了よ♪
                    finish()
                }
                ivProgress.setImageBitmap(state.currentImage)
                pbGeneration.progress = (state.progress * 100).toInt()
                tvPercent.text = "${(state.progress * 100).toInt()}%"
                
                if (state.totalBatch > 1) {
                    tvBatchCount.visibility = View.VISIBLE
                    tvBatchCount.text = "${state.currentBatch}/${state.totalBatch}"
                } else {
                    tvBatchCount.visibility = View.GONE
                }
                
                if (isInPictureInPictureMode) {
                    updatePiPParams()
                }
            }
        }
    }

    private fun updatePiPParams() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val actions = ArrayList<RemoteAction>()

            // ストップボタン
            val stopIntent = PendingIntent.getBroadcast(this, 1, Intent(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
            actions.add(RemoteAction(Icon.createWithResource(this, R.drawable.ic_stop_gen), "中止", "中止", stopIntent))

            // スキップボタン
            val skipIntent = PendingIntent.getBroadcast(this, 2, Intent(ACTION_SKIP), PendingIntent.FLAG_IMMUTABLE)
            actions.add(RemoteAction(Icon.createWithResource(this, R.drawable.ic_skip_gen), "スキップ", "スキップ", skipIntent))

            // 最大化ボタン
            val maxIntent = PendingIntent.getBroadcast(this, 3, Intent(ACTION_MAXIMIZE), PendingIntent.FLAG_IMMUTABLE)
            actions.add(RemoteAction(Icon.createWithResource(this, R.drawable.ic_maximize), "最大化", "最大化", maxIntent))

            val params = PictureInPictureParams.Builder()
                .setActions(actions)
                .build()
            setPictureInPictureParams(params)
        }
    }

    override fun onUserLeaveHint() {
        // ホームボタンを押した時とかに自動でPiPに入るわよ！
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            enterPictureInPictureMode(PictureInPictureParams.Builder().build())
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            llControls.visibility = View.GONE // PiP中はRemoteActionを使うわ
            pbGeneration.visibility = View.GONE
        } else {
            llControls.visibility = View.VISIBLE
            pbGeneration.visibility = View.VISIBLE
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isPipActive = false
        unregisterReceiver(receiver)
    }
}
