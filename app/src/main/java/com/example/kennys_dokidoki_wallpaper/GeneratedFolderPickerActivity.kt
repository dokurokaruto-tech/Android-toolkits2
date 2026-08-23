package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import kotlinx.coroutines.launch

/**
 * Keeps the generated-date picker as an Activity in the back stack.
 * MainActivity -> this picker -> AlbumDetailActivity means neither forward nor back
 * navigation exposes the prompt builder while network data is being fetched.
 */
class GeneratedFolderPickerActivity : AppCompatActivity() {
    private data class FolderItem(
        val name: String,
        val count: Int,
        val thumbnail: Any?,
        val localFolder: DocumentFile? = null,
        val remoteDate: String? = null
    )

    private lateinit var recyclerView: RecyclerView
    private lateinit var loadingOverlay: View
    private lateinit var emptyText: TextView
    private var awaitingAlbumReturn = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_generated_folder_picker)
        sizeDialogWindow()

        recyclerView = findViewById(R.id.recycler_view_tags)
        loadingOverlay = findViewById(R.id.generated_folders_loading_overlay)
        emptyText = findViewById(R.id.tv_generated_folders_empty)
        recyclerView.layoutManager = GridLayoutManager(this, 2)

        // Keep the existing folder-card presentation; remove tag-editor-only controls.
        findViewById<Button>(R.id.btn_dialog_done).visibility = View.GONE
        findViewById<View>(R.id.btn_add_tag).visibility = View.GONE
        (findViewById<View>(R.id.dialog_title).parent as? View)?.visibility = View.GONE
        val includedRoot = recyclerView.parent as? LinearLayout
        if (includedRoot != null && includedRoot.childCount > 1) {
            includedRoot.getChildAt(1).visibility = View.GONE
        }

        loadFolders(reportRemoteFailure = true)
    }

    override fun onStart() {
        super.onStart()
        sizeDialogWindow()
    }

    override fun onResume() {
        super.onResume()
        if (awaitingAlbumReturn) {
            awaitingAlbumReturn = false
            setLoading(false)
            loadFolders(reportRemoteFailure = false)
        }
    }

    private fun sizeDialogWindow() {
        val metrics = resources.displayMetrics
        window.setLayout(
            (metrics.widthPixels * 0.94f).toInt(),
            (metrics.heightPixels * 0.86f).toInt()
        )
    }

    private fun loadFolders(reportRemoteFailure: Boolean) {
        setLoading(true)
        lifecycleScope.launch {
            var remoteFailure: AgentConnectionDiagnosis? = null
            val items = try {
                val probe = GenerationAgentClient.probe(this@GeneratedFolderPickerActivity, "閲覧")
                if (probe.code == AgentConnectionClassifier.OK ||
                    probe.code == AgentConnectionClassifier.SD_DOWN
                ) {
                    try {
                        val remote = GenerationAgentClient.fetchFolders(this@GeneratedFolderPickerActivity).map {
                            FolderItem(it.date, it.count, it.thumbnailUrl, remoteDate = it.date)
                        }
                        val remoteNames = remote.map { it.name }.toSet()
                        val local = loadLocalFolders().map {
                            if (it.name in remoteNames) it.copy(name = "${it.name} (端末)") else it
                        }
                        remote + local
                    } catch (error: Exception) {
                        remoteFailure = AgentConnectionLog.last
                            ?: AgentConnectionClassifier.fromException(error)
                        loadLocalFolders()
                    }
                } else {
                    remoteFailure = probe
                    loadLocalFolders()
                }
            } catch (error: Exception) {
                remoteFailure = AgentConnectionLog.last
                    ?: AgentConnectionClassifier.fromException(error)
                loadLocalFolders()
            }

            recyclerView.adapter = FolderAdapter(items, ::onFolderSelected)
            if (items.isEmpty()) {
                emptyText.visibility = View.VISIBLE
                emptyText.text = if (remoteFailure != null) {
                    "PCから日付フォルダを取れなかった。\n[${remoteFailure.code}] ${remoteFailure.title}"
                } else {
                    "まだ画像が生成されていません。\nPC生成エージェントの接続を確認してください。"
                }
            } else {
                emptyText.visibility = View.GONE
            }
            setLoading(false)
            if (reportRemoteFailure && remoteFailure != null) {
                AgentConnectionUi.showDiagnosis(
                    this@GeneratedFolderPickerActivity,
                    remoteFailure,
                    "PCの閲覧一覧を取得できない"
                )
            }
        }
    }

    private fun onFolderSelected(folder: FolderItem) {
        if (folder.remoteDate != null) {
            openRemoteFolder(folder.remoteDate)
        } else {
            folder.localFolder?.let(::openLocalFolder)
        }
    }

    private fun openRemoteFolder(date: String) {
        setLoading(true)
        lifecycleScope.launch {
            try {
                val images = GenerationAgentClient.fetchImages(this@GeneratedFolderPickerActivity, date)
                if (images.isEmpty()) {
                    setLoading(false)
                    Toast.makeText(this@GeneratedFolderPickerActivity, "この日付の画像はありません。", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                images.forEach { image ->
                    GeneratedImageDraftStore.seedGeneratedSource(
                        this@GeneratedFolderPickerActivity,
                        Uri.parse(image.url),
                        image.tags,
                        image.cardStates,
                        image.width,
                        image.height,
                        image.steps,
                        image.sampler,
                        image.prompt,
                        image.randomPickedIds,
                        image.randomEnabledCategories
                    )
                }
                openAlbum(
                    albumName = "生成: $date",
                    uris = images.map { it.url },
                    thumbnailUris = images.map { it.thumbnailUrl },
                    tagLists = images.map { GeneratedImageTagBinding.encodeTagList(it.tags) },
                    remoteDate = date
                )
            } catch (error: Exception) {
                setLoading(false)
                AgentConnectionUi.showDiagnosis(
                    this@GeneratedFolderPickerActivity,
                    AgentConnectionLog.last ?: AgentConnectionClassifier.fromException(error),
                    "PC画像一覧を取得できない"
                )
            }
        }
    }

    private fun openLocalFolder(folder: DocumentFile) {
        val files = folder.listFiles().filter(::isGeneratedImageFile)
        openAlbum(
            albumName = "生成: ${folder.name ?: "Unknown"}",
            uris = files.map { it.uri.toString() },
            folderUri = folder.uri.toString()
        )
    }

    private fun openAlbum(
        albumName: String,
        uris: List<String>,
        thumbnailUris: List<String>? = null,
        tagLists: List<String>? = null,
        remoteDate: String? = null,
        folderUri: String? = null
    ) {
        awaitingAlbumReturn = true
        startActivity(Intent(this, AlbumDetailActivity::class.java).apply {
            putExtra("ALBUM_NAME", albumName)
            putExtra("FROM_GENERATED_FOLDER_PICKER", true)
            putExtra("REMOTE_GENERATED", remoteDate != null)
            remoteDate?.let { putExtra("REMOTE_DATE", it) }
            folderUri?.let { putExtra("FOLDER_URI", it) }
            putStringArrayListExtra("VIRTUAL_ALBUM_URIS", ArrayList(uris))
            thumbnailUris?.let {
                putStringArrayListExtra("VIRTUAL_ALBUM_THUMBNAIL_URIS", ArrayList(it))
            }
            tagLists?.let {
                putStringArrayListExtra("VIRTUAL_ALBUM_TAGS", ArrayList(it))
            }
        })
        // Do not animate through the prompt builder between the two generated-image screens.
        overridePendingTransition(0, 0)
    }

    private fun loadLocalFolders(): List<FolderItem> {
        val folderUri = getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getString("gen_save_folder_uri", null)?.let(Uri::parse) ?: return emptyList()
        val root = DocumentFile.fromTreeUri(this, folderUri)?.takeIf { it.exists() } ?: return emptyList()
        return root.listFiles()
            .filter { it.isDirectory }
            .sortedByDescending { it.name }
            .map { folder ->
                val images = folder.listFiles().filter(::isGeneratedImageFile)
                FolderItem(
                    name = folder.name ?: "Unknown",
                    count = images.size,
                    thumbnail = images.firstOrNull()?.uri,
                    localFolder = folder
                )
            }
            .filter { it.count > 0 }
    }

    private fun isGeneratedImageFile(file: DocumentFile): Boolean =
        file.isFile && (file.type?.startsWith("image/") == true ||
            file.name?.lowercase()?.let {
                it.endsWith(".png") || it.endsWith(".jpg") ||
                    it.endsWith(".jpeg") || it.endsWith(".webp")
            } == true)

    private fun setLoading(loading: Boolean) {
        loadingOverlay.visibility = if (loading) View.VISIBLE else View.GONE
    }

    private class FolderAdapter(
        private val folders: List<FolderItem>,
        private val onClick: (FolderItem) -> Unit
    ) : RecyclerView.Adapter<FolderAdapter.ViewHolder>() {
        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val thumbnail: ImageView = view.findViewById(R.id.iv_folder_thumbnail)
            val name: TextView = view.findViewById(R.id.tv_folder_name)
            val count: TextView = view.findViewById(R.id.tv_image_count)
            val card: View = view.findViewById(R.id.card_folder)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            return ViewHolder(
                LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_generated_folder, parent, false)
            )
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val folder = folders[position]
            holder.name.text = folder.name
            holder.count.text = "${folder.count} 枚"
            if (folder.thumbnail != null) {
                val uri = Uri.parse(folder.thumbnail.toString())
                Glide.with(holder.thumbnail)
                    .load(folder.thumbnail)
                    .diskCacheStrategy(ImageStoragePolicy.glideDiskCache(uri))
                    .centerCrop()
                    .into(holder.thumbnail)
            } else {
                Glide.with(holder.thumbnail).clear(holder.thumbnail)
                holder.thumbnail.setImageResource(R.drawable.ic_folder)
                holder.thumbnail.scaleType = ImageView.ScaleType.CENTER_INSIDE
            }
            holder.card.setOnClickListener { onClick(folder) }
        }

        override fun getItemCount(): Int = folders.size
    }
}
