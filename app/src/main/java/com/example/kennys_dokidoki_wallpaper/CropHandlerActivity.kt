package com.example.kennys_dokidoki_wallpaper

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.DisplayMetrics
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class CropHandlerActivity : AppCompatActivity() {

    private val cropLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data
            if (data != null) {
                val left = data.getFloatExtra("CROP_LEFT", 0f)
                val top = data.getFloatExtra("CROP_TOP", 0f)
                val right = data.getFloatExtra("CROP_RIGHT", 1f)
                val bottom = data.getFloatExtra("CROP_BOTTOM", 1f)
                
                DataManager.loadData(this)
                
                // 元の画像のURIをキーにして、既存のエントリーを探す
                val imageUriStr = intent.getStringExtra("IMAGE_URI")
                val entry = DataManager.allImages.find { it.uri.toString() == imageUriStr }
                
                if (entry != null) {
                    // 非破壊的に範囲だけを保存
                    entry.cropRect = android.graphics.RectF(left, top, right, bottom)
                    entry.croppedUri = null // 古い形式のデータはクリアするわ
                    DataManager.saveData(this)
                    
                    // 壁紙サービスに変更を通知（即座に反映させるため）
                    // DataManager.saveData 内で settings の変更が走るから、Service側のリスナーでも検知されるはずだけど、
                    // 念のためブロードキャストも送っておくわね！
                    sendBroadcast(Intent("com.example.kennys_dokidoki_wallpaper.ACTION_WALLPAPER_CHANGED"))
                    Toast.makeText(this, "クロップ範囲を更新しました。", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "画像が見つからなかったわ…", Toast.LENGTH_SHORT).show()
                }
            }
        }
        
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sourceUriStr = intent.getStringExtra("SOURCE_URI")
        if (sourceUriStr == null) {
            finish()
            return
        }

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        val aspectX = metrics.widthPixels
        val aspectY = metrics.heightPixels

        val cropIntent = Intent(this, CustomCropActivity::class.java).apply {
            putExtra("SOURCE_URI", sourceUriStr) // 常に元画像のURIからクロップを開始
            putExtra("ASPECT_X", aspectX)
            putExtra("ASPECT_Y", aspectY)
        }
        cropLauncher.launch(cropIntent)
    }
}
