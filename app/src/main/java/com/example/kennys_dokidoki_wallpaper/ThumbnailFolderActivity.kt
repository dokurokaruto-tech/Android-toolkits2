package com.example.kennys_dokidoki_wallpaper

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 端末に保存したサムネイルの中身を見る画面。
 * Android 10 以降はエクスプローラーが Android/data 配下に入れず、
 * 初期位置の指定もストレージ直下へフォールバックする。だから自前で一覧する。
 */
class ThumbnailFolderActivity : AppCompatActivity() {

    private lateinit var recycler: RecyclerView
    private lateinit var header: TextView
    private lateinit var emptyView: TextView
    private lateinit var overlay: View
    private lateinit var overlayImage: ImageView
    private var files: List<File> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_thumbnail_folder)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar_thumbnail_folder)
        toolbar.setNavigationOnClickListener { finish() }

        header = findViewById(R.id.tv_folder_header)
        recycler = findViewById(R.id.recycler_thumbnail_files)
        emptyView = findViewById(R.id.tv_folder_empty)
        overlay = findViewById(R.id.overlay_preview)
        overlayImage = findViewById(R.id.iv_overlay_image)
        overlay.setOnClickListener { overlay.visibility = View.GONE }

        findViewById<MaterialButton>(R.id.btn_open_explorer).setOnClickListener {
            openInExplorer()
        }
        recycler.layoutManager = LinearLayoutManager(this)
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        lifecycleScope.launch {
            files = withContext(Dispatchers.IO) {
                ThumbnailLocalCache.listFiles(this@ThumbnailFolderActivity)
            }
            val dir = withContext(Dispatchers.IO) {
                ThumbnailLocalCache.cacheDir(applicationContext)
            }
            val (bytes, count) = withContext(Dispatchers.IO) {
                ThumbnailLocalCache.usage(applicationContext)
            }
            header.text = "${dir.absolutePath}\n${count}件・合計 %s".format(
                ThumbnailStoragePolicy.usageLabel(bytes)
            )
            recycler.adapter = FileAdapter()
            emptyView.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
            recycler.visibility = if (files.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun showOverlay(file: File) {
        Glide.with(this).load(file).into(overlayImage)
        overlay.visibility = View.VISIBLE
    }

    private fun confirmDelete(file: File) {
        AlertDialog.Builder(this, R.style.Theme_Kennys_dokidoki_wallpaper)
            .setTitle("サムネイルを削除")
            .setMessage(
                "${file.name} を削除する。\n" +
                    "カード／プリセットの分は次回起動時にPCから自動で戻り、" +
                    "閲覧キャッシュは次に表示したとき自動で取り直す。"
            )
            .setPositiveButton("削除") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    file.delete()
                    withContext(Dispatchers.Main) { reload() }
                }
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    /**
     * システムのエクスプローラーを試す。Android 9 以前や全ファイル
     * アクセスを持つファイラなら実際に開ける。開けなければ自前の一覧が本命。
     */
    private fun openInExplorer() {
        lifecycleScope.launch {
            val dir = withContext(Dispatchers.IO) {
                ThumbnailLocalCache.cacheDir(applicationContext)
            }
            val documentId = FolderExplorerPolicy.documentId(dir.absolutePath)
            if (documentId == null) {
                Toast.makeText(
                    this@ThumbnailFolderActivity,
                    "このフォルダはアプリ専用領域のため、エクスプローラーからは開けません。\n${dir.absolutePath}",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            val docUri = DocumentsContract.buildDocumentUri(
                "com.android.externalstorage.documents",
                documentId
            )
            val view = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(docUri, DocumentsContract.Document.MIME_TYPE_DIR)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                startActivity(view)
                return@launch
            } catch (_: Exception) {
            }
            try {
                startActivity(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                    putExtra(DocumentsContract.EXTRA_INITIAL_URI, docUri)
                })
            } catch (_: Exception) {
                Toast.makeText(this@ThumbnailFolderActivity, dir.absolutePath, Toast.LENGTH_LONG).show()
            }
        }
    }

    private inner class FileAdapter : RecyclerView.Adapter<FileHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileHolder = FileHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_thumbnail_file, parent, false)
        )

        override fun onBindViewHolder(holder: FileHolder, position: Int) {
            val file = files[position]
            holder.name.text = "${ThumbnailFolderPolicy.kindLabel(file.name)}・" +
                ThumbnailFolderPolicy.displayName(file.name)
            holder.size.text = ThumbnailFolderPolicy.sizeLabel(file.length())
            Glide.with(holder.preview)
                .load(file)
                .override(160, 160)
                .centerCrop()
                .into(holder.preview)
            holder.itemView.setOnClickListener { showOverlay(file) }
            holder.delete.setOnClickListener { confirmDelete(file) }
        }

        override fun getItemCount(): Int = files.size
    }

    class FileHolder(view: View) : RecyclerView.ViewHolder(view) {
        val preview: ImageView = view.findViewById(R.id.iv_file_preview)
        val name: TextView = view.findViewById(R.id.tv_file_name)
        val size: TextView = view.findViewById(R.id.tv_file_size)
        val delete: ImageButton = view.findViewById(R.id.btn_file_delete)
    }
}
