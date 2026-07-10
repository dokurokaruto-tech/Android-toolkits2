package com.example.kennys_dokidoki_wallpaper

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.util.DisplayMetrics
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.viewpager2.widget.ViewPager2
import java.io.File

class ImagePreviewActivity : AppCompatActivity() {

    private lateinit var viewPager: ViewPager2
    private lateinit var pagerAdapter: ImagePreviewPagerAdapter
    private lateinit var tvFileInfo: TextView

    private var albumName: String = ""
    private val currentEntries = mutableListOf<ImageEntry>()

    private val cropLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val data = result.data
            if (data != null) {
                val left = data.getFloatExtra("CROP_LEFT", 0f)
                val top = data.getFloatExtra("CROP_TOP", 0f)
                val right = data.getFloatExtra("CROP_RIGHT", 1f)
                val bottom = data.getFloatExtra("CROP_BOTTOM", 1f)
                
                val currentIndex = viewPager.currentItem
                val entry = currentEntries[currentIndex]
                
                entry.cropRect = RectF(left, top, right, bottom)
                entry.croppedUri = null 
                
                DataManager.saveData(this)
                sendBroadcast(Intent("com.example.kennys_dokidoki_wallpaper.ACTION_WALLPAPER_CHANGED"))
                pagerAdapter.notifyItemChanged(currentIndex)
                updateFileInfo()
                Toast.makeText(this, "クロップ範囲を保存しました。", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val fullScreenLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val finalIndex = result.data?.getIntExtra("FINAL_INDEX", -1) ?: -1
            if (finalIndex != -1 && finalIndex != viewPager.currentItem) {
                // 全画面で見ていた画像に合わせて、カルーセルの位置も同期させるわよ！
                viewPager.setCurrentItem(finalIndex, false)
                updateFileInfo()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_image_preview)

        findViewById<ImageView>(R.id.btn_back).setOnClickListener { 
            returnResultAndFinish()
        }
        tvFileInfo = findViewById(R.id.tv_file_info)

        albumName = intent.getStringExtra("ALBUM_NAME") ?: ""
        val startIndex = intent.getIntExtra("START_INDEX", 0)

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

        viewPager = findViewById(R.id.image_view_pager)
        pagerAdapter = ImagePreviewPagerAdapter(currentEntries, this) { position ->
            // 今見ているリストの「正しい位置」を全画面表示に伝えるわよ！
            val intent = Intent(this, FullScreenImageActivity::class.java).apply {
                putExtra("ALBUM_NAME", albumName)
                putExtra("START_INDEX", viewPager.currentItem)
            }
            fullScreenLauncher.launch(intent)
        }
        viewPager.adapter = pagerAdapter
        viewPager.offscreenPageLimit = 3
        
        viewPager.setPageTransformer { page, position ->
            val pageMargin = resources.displayMetrics.density * 24
            val pageOffset = resources.displayMetrics.density * 32
            val offset = position * -(2 * pageOffset + pageMargin)
            page.translationX = offset
            
            val scale = 0.85f + (1f - Math.abs(position)) * 0.15f
            page.scaleY = scale
            page.scaleX = scale
            page.alpha = 0.5f + (1f - Math.abs(position)) * 0.5f
            page.translationZ = -Math.abs(position)
        }

        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateFileInfo()
            }
        })

        viewPager.setCurrentItem(startIndex.coerceIn(0, currentEntries.lastIndex), false)

        findViewById<View>(R.id.btn_preview_start_now).setOnClickListener { startWallpaperFromCurrent() }
        findViewById<View>(R.id.btn_preview_crop).setOnClickListener { launchCropForCurrent() }
        findViewById<View>(R.id.btn_preview_delete).setOnClickListener { deleteCurrentImage() }
    }

    override fun onBackPressed() {
        returnResultAndFinish()
    }

    private fun returnResultAndFinish() {
        val intent = Intent().apply {
            putExtra("FINAL_INDEX", viewPager.currentItem)
        }
        setResult(RESULT_OK, intent)
        finish()
    }

    private fun updateFileInfo() {
        if (currentEntries.isEmpty()) return
        val index = viewPager.currentItem
        val entry = currentEntries[index]
        
        val path = entry.uri.path ?: "Unknown path"
        val cropStatus = if (entry.cropRect != null) "[Custom Crop]" else if (entry.croppedUri != null) "[Legacy Crop]" else "[Original]"
        tvFileInfo.text = "$path\n$cropStatus"
    }

    private fun startWallpaperFromCurrent() {
        if (currentEntries.isEmpty()) return
        val currentIndex = viewPager.currentItem
        val currentEntry = currentEntries[currentIndex]

        if (!currentEntry.isActive) {
            currentEntry.isActive = true
            DataManager.saveData(this)
        }

        val settingsPrefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val editor = settingsPrefs.edit()
        editor.putString("active_album_name", albumName)
        
        if (albumName.isNotEmpty()) {
            val set = DataManager.imageSetList.find { it.name == albumName }
            if (set != null) {
                val activeEntries = set.filterImages(DataManager.allImages).filter { it.isActive }
                val albumIndex = activeEntries.indexOfFirst { it.uri == currentEntry.uri }
                if (albumIndex != -1) {
                    editor.putInt("last_index_for_album_$albumName", albumIndex)
                    editor.putInt("active_image_index", albumIndex)
                } else {
                    editor.putInt("last_index_for_album_$albumName", 0)
                }
            }
        } else {
            val activeGlobal = DataManager.allImages.filter { it.isActive }
            val globalIndex = activeGlobal.indexOfFirst { it.uri == currentEntry.uri }
            if (globalIndex != -1) {
                editor.putInt("active_image_index", globalIndex)
            }
        }
        editor.apply()

        val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
            putExtra(
                WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                ComponentName(this@ImagePreviewActivity, MyWallpaperService::class.java)
            )
        }
        startActivity(intent)
    }

    private fun launchCropForCurrent() {
        if (currentEntries.isEmpty()) return
        val entry = currentEntries[viewPager.currentItem]
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        val intent = Intent(this, CustomCropActivity::class.java).apply {
            putExtra("SOURCE_URI", entry.uri.toString())
            putExtra("ASPECT_X", metrics.widthPixels)
            putExtra("ASPECT_Y", metrics.heightPixels)
        }
        cropLauncher.launch(intent)
    }

    private fun deleteCurrentImage() {
        if (currentEntries.isEmpty()) return
        val index = viewPager.currentItem
        val entry = currentEntries[index]

        AlertDialog.Builder(this)
            .setTitle("画像の削除")
            .setMessage("この画像をどうする？")
            .setPositiveButton("リストから外す") { _, _ -> performDelete(index, entry, false) }
            .setNeutralButton("ファイルごと削除") { _, _ -> performDelete(index, entry, true) }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun performDelete(index: Int, entry: ImageEntry, deleteFile: Boolean) {
        if (deleteFile) {
            if (!DataManager.deleteImageFile(this, entry.uri)) {
                Toast.makeText(this, "ファイルが消せなかったわ…", Toast.LENGTH_LONG).show()
                return
            }
        }
        currentEntries.removeAt(index)
        pagerAdapter.notifyItemRemoved(index)
        DataManager.allImages.remove(entry)
        DataManager.saveData(this)
        if (currentEntries.isEmpty()) finish() else updateFileInfo()
    }
}
