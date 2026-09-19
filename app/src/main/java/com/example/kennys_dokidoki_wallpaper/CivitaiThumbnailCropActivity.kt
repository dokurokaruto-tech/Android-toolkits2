package com.example.kennys_dokidoki_wallpaper

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.canhub.cropper.CropImageView
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

// Crops a Civitai showcase image into a 9:16 card thumbnail.
class CivitaiThumbnailCropActivity : AppCompatActivity() {

    private lateinit var cropView: CropImageView
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    companion object {
        const val EXTRA_SOURCE_PATH = "SOURCE_PATH"
        const val EXTRA_CROPPED_PATH = "CROPPED_PATH"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_civitai_thumbnail_crop)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar_crop)
        toolbar.setNavigationOnClickListener { finish() }
        cropView = findViewById(R.id.crop_thumb)

        val source = intent.getStringExtra(EXTRA_SOURCE_PATH)
        if (source.isNullOrBlank() || !File(source).isFile) {
            Toast.makeText(this, "画像が見つかりません。", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        cropView.setImageUriAsync(Uri.fromFile(File(source)))
        cropView.setAspectRatio(9, 16)
        cropView.setFixedAspectRatio(true)
        cropView.guidelines = CropImageView.Guidelines.ON

        findViewById<android.view.View>(R.id.btn_crop_cancel).setOnClickListener { finish() }
        findViewById<android.view.View>(R.id.btn_crop_save).setOnClickListener { saveCropped() }
    }

    private fun saveCropped() {
        val bitmap = cropView.croppedImage
        if (bitmap == null) {
            Toast.makeText(this, "範囲が取得できなかったわ…", Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch {
            val saved = withContext(Dispatchers.IO) { writeJpeg(bitmap) }
            if (saved == null) {
                Toast.makeText(this@CivitaiThumbnailCropActivity, "保存に失敗しました。", Toast.LENGTH_SHORT).show()
                return@launch
            }
            setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_CROPPED_PATH, saved.absolutePath))
            finish()
        }
    }

    private fun writeJpeg(bitmap: Bitmap): File? {
        return try {
            val dir = File(filesDir, "civitai_thumbs").apply { mkdirs() }
            val out = File(dir, "crop_${System.currentTimeMillis()}.jpg")
            FileOutputStream(out).use { stream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
            }
            out
        } catch (_: Exception) {
            null
        }
    }

    override fun onDestroy() {
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }
}
