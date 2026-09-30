package com.example.kennys_dokidoki_wallpaper

import android.os.Bundle
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

/**
 * 1日分の日記を書く画面。文字はいくらでも書け、画像は用紙の好きな場所へ置ける。
 */
class DiaryEditorActivity : AppCompatActivity() {

    private lateinit var dateKey: String
    private lateinit var editor: EditText
    private lateinit var canvas: FrameLayout
    private lateinit var photoLayer: DiaryPhotoLayer

    private val pickImage = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) {
            return@registerForActivityResult
        }
        val fileName = contentResolver.openInputStream(uri)?.use { DiaryStore.importImage(this, it) }
        if (fileName == null) {
            Toast.makeText(this, "画像を読み込めませんでした。", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        // 置いた直後にすぐ動かせるよう、用紙の上のほう中央に落とす
        photoLayer.add(
            DiaryPhoto(
                fileName = fileName,
                xRatio = (1f - DiaryLayoutPolicy.DEFAULT_WIDTH_RATIO) / 2f,
                yRatio = 0.05f,
                widthRatio = DiaryLayoutPolicy.DEFAULT_WIDTH_RATIO
            )
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_diary_editor)
        dateKey = intent.getStringExtra(EXTRA_DATE) ?: DiaryStore.todayKey()

        editor = findViewById(R.id.et_diary_text)
        canvas = findViewById(R.id.diary_canvas)
        photoLayer = DiaryPhotoLayer(canvas) { handle -> confirmDelete(handle) }

        findViewById<TextView>(R.id.tv_editor_date).text = DiaryStore.displayDate(dateKey)
        findViewById<ImageButton>(R.id.btn_editor_back).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.btn_editor_add_image).setOnClickListener {
            pickImage.launch("image/*")
        }
        findViewById<MaterialButton>(R.id.btn_editor_save).setOnClickListener {
            save()
            Toast.makeText(this, "保存しました。", Toast.LENGTH_SHORT).show()
        }

        restore()
    }

    private fun restore() {
        val entry = DiaryStore.load(this, dateKey)
        editor.setText(entry.text)
        photoLayer.restore(entry.photos)
    }

    private fun save() {
        val entry = DiaryEntry(dateKey, editor.text.toString(), photoLayer.snapshot())
        DiaryStore.save(this, entry)
        // 連携済みなら保存のたびに Drive を追いかけさせる
        DiaryDriveSync.pushInBackground(this, entry)
    }

    private fun confirmDelete(handle: DiaryPhotoHandle) {
        AlertDialog.Builder(this)
            .setTitle("この画像を削除しますか？")
            .setPositiveButton("削除") { _, _ ->
                photoLayer.remove(handle)
                save()
                DiaryStore.purgeUnusedImages(this)
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    // 画面を離れても書きかけを失わない
    override fun onPause() {
        super.onPause()
        save()
    }

    companion object {
        const val EXTRA_DATE = "DIARY_DATE"
    }
}
