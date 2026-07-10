package com.example.kennys_dokidoki_wallpaper

import android.app.Activity
import android.content.Intent
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.canhub.cropper.CropImageView

class CustomCropActivity : AppCompatActivity() {

    private lateinit var cropImageView: CropImageView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_custom_crop)

        cropImageView = findViewById(R.id.cropImageView)
        
        val sourceUriStr = intent.getStringExtra("SOURCE_URI")
        val aspectX = intent.getIntExtra("ASPECT_X", 9)
        val aspectY = intent.getIntExtra("ASPECT_Y", 16)

        Log.d("CustomCropActivity", "onCreate: sourceUriStr=$sourceUriStr, aspectX=$aspectX, aspectY=$aspectY")

        if (sourceUriStr != null) {
            val uri = Uri.parse(sourceUriStr)
            try {
                cropImageView.setImageUriAsync(uri)
                cropImageView.setAspectRatio(aspectX, aspectY)
                cropImageView.setFixedAspectRatio(true)
                cropImageView.guidelines = CropImageView.Guidelines.ON
            } catch (e: Exception) {
                Log.e("CustomCropActivity", "Error setting image URI: ${e.message}", e)
                Toast.makeText(this, "画像の読み込みに失敗しました", Toast.LENGTH_SHORT).show()
                finish()
            }
        } else {
            Toast.makeText(this, "画像が見つかりません。", Toast.LENGTH_SHORT).show()
            finish()
        }

        findViewById<ImageView>(R.id.btn_cancel).setOnClickListener {
            finish()
        }

        findViewById<ImageView>(R.id.btn_reset).setOnClickListener {
            cropImageView.resetCropRect()
        }

        findViewById<ImageView>(R.id.btn_confirm).setOnClickListener {
            // クロップ後のビットマップを保存するのではなく、選択範囲（正規化された座標）を取得するわ
            val rect = cropImageView.cropRect
            val whole = cropImageView.wholeImageRect
            
            if (rect != null && whole != null) {
                // 0.0 〜 1.0 の比率に変換して保存するわね。これで元画像の解像度が変わっても大丈夫よ。
                val left = rect.left.toFloat() / whole.width()
                val top = rect.top.toFloat() / whole.height()
                val right = rect.right.toFloat() / whole.width()
                val bottom = rect.bottom.toFloat() / whole.height()
                
                val resultIntent = Intent().apply {
                    putExtra("CROP_LEFT", left)
                    putExtra("CROP_TOP", top)
                    putExtra("CROP_RIGHT", right)
                    putExtra("CROP_BOTTOM", bottom)
                }
                setResult(Activity.RESULT_OK, resultIntent)
                finish()
            } else {
                Toast.makeText(this, "範囲が取得できなかったわ…", Toast.LENGTH_SHORT).show()
            }
        }
    }
}