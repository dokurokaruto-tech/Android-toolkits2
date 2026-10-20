package com.example.kennys_dokidoki_wallpaper

import android.os.Bundle
import android.view.MenuItem
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText

/** 1日分の日記を書く画面。文字はいくらでも書け、画像は用紙の好きな場所へ置ける。 */
class DiaryEditorActivity : AppCompatActivity() {

    private lateinit var dateKey: String
    private lateinit var editor: TextInputEditText
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

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar_diary_editor)
        toolbar.title = DiaryStore.displayDate(dateKey)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.setOnMenuItemClickListener(::onToolbarMenuItemClick)

        restore()
    }

    private fun onToolbarMenuItemClick(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_diary_add_image -> {
            pickImage.launch("image/*")
            true
        }
        R.id.action_diary_save -> {
            save()
            Toast.makeText(this, "保存しました。", Toast.LENGTH_SHORT).show()
            true
        }
        else -> false
    }

    private fun restore() {
        val entry = DiaryStore.load(this, dateKey)
        editor.setText(entry.text)
        photoLayer.restore(entry.photos)
    }

    private fun save() {
        val entry = DiaryEntry(dateKey, editor.text?.toString().orEmpty(), photoLayer.snapshot())
        DiaryStore.save(this, entry)
        // 連携済みなら保存のたびに Drive を追いかけさせる
        DiaryDriveSync.syncInBackground(this)
    }

    private fun confirmDelete(handle: DiaryPhotoHandle) {
        MaterialAlertDialogBuilder(this)
            .setTitle("この画像を削除しますか？")
            .setMessage("この日記から画像を取り除きます。")
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
