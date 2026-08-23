package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.launch

/**
 * 生成画像の日付フォルダを全画面で選ぶ。
 * MainActivity -> この画面 -> AlbumDetailActivity なので、往復でビルダーが一瞬出ない。
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
    private lateinit var emptyState: View
    private lateinit var emptyMessage: TextView
    private var awaitingAlbumReturn = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_generated_folder_picker)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.generated_folders_root)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar_generated_folders)
        toolbar.title = GeneratedFolderPickerPolicy.TITLE
        toolbar.subtitle = GeneratedFolderPickerPolicy.SUBTITLE
        toolbar.setNavigationOnClickListener { finish() }

        recyclerView = findViewById(R.id.recycler_view_tags)
        loadingOverlay = findViewById(R.id.generated_folders_loading_overlay)
        emptyState = findViewById(R.id.tv_generated_folders_empty)
        emptyMessage = findViewById(R.id.tv_generated_folders_empty_message)
        recyclerView.layoutManager = GridLayoutManager(this, 2)

        val autoDate = intent.getStringExtra(GenerationPipExpandPolicy.EXTRA_AUTO_OPEN_DATE)
        if (GenerationPipExpandPolicy.shouldAutoOpen(autoDate)) {
            openRemoteFolder(
                date = autoDate!!,
                allowEmpty = intent.getBooleanExtra(GenerationPipExpandPolicy.EXTRA_ALLOW_EMPTY_FOLDER, false),
                thenShowLivePreview = intent.getBooleanExtra(
                    GenerationPipExpandPolicy.EXTRA_SHOW_LIVE_PREVIEW,
                    false
                )
            )
        } else {
            showInstantFolders()
            loadFolders(reportRemoteFailure = true)
        }
        observeLiveFolders()
    }

    override fun onStart() {
        super.onStart()
        GeneratedLibraryLiveUpdate.bind(this, GenerationPipExpandPolicy.todayDate())
    }

    override fun onStop() {
        GeneratedLibraryLiveUpdate.unbind()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (awaitingAlbumReturn) {
            awaitingAlbumReturn = false
            loadFolders(reportRemoteFailure = false)
        }
    }

    private fun observeLiveFolders() {
        lifecycleScope.launch {
            GeneratedLibraryLiveUpdate.snapshot.collect { snapshot ->
                if (snapshot.folders.isEmpty() || !::recyclerView.isInitialized) return@collect
                applyRemoteFolders(snapshot.folders)
            }
        }
    }

    private fun applyRemoteFolders(remote: List<AgentGeneratedFolder>) {
        persistRemoteCache(remote)
        val combined = combineFolders(
            remote.map { FolderItem(it.date, it.count, it.thumbnailUrl, remoteDate = it.date) },
            loadLocalFolders()
        )
        recyclerView.adapter = FolderAdapter(combined, ::onFolderSelected)
        emptyState.visibility = if (combined.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showInstantFolders() {
        val items = instantFolderItems()
        if (items.isEmpty()) return
        recyclerView.adapter = FolderAdapter(items, ::onFolderSelected)
        emptyState.visibility = View.GONE
    }

    private fun instantFolderItems(): List<FolderItem> {
        val live = GeneratedLibraryLiveUpdate.snapshot.value.folders
        val cached = if (live.isNotEmpty()) {
            live.map { GeneratedFolderCachePolicy.Entry(it.date, it.count, it.thumbnailUrl) }
        } else {
            GeneratedFolderCachePolicy.decode(
                getSharedPreferences("settings", Context.MODE_PRIVATE)
                    .getString(GeneratedFolderCachePolicy.KEY, null)
            )
        }
        return combineFolders(
            cached.map { FolderItem(it.date, it.count, it.thumbnailUrl, remoteDate = it.date) },
            loadLocalFolders()
        )
    }

    private fun combineFolders(remote: List<FolderItem>, local: List<FolderItem>): List<FolderItem> {
        val remoteNames = remote.map { it.name }.toSet()
        val taggedLocal = local.map {
            if (it.name in remoteNames) it.copy(name = "${it.name} (端末)") else it
        }
        return remote + taggedLocal
    }

    private fun persistRemoteCache(remote: List<AgentGeneratedFolder>) {
        getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
            .putString(
                GeneratedFolderCachePolicy.KEY,
                GeneratedFolderCachePolicy.encode(
                    remote.map { GeneratedFolderCachePolicy.Entry(it.date, it.count, it.thumbnailUrl) }
                )
            )
            .apply()
    }

    private fun loadFolders(reportRemoteFailure: Boolean) {
        lifecycleScope.launch {
            var remoteFailure: AgentConnectionDiagnosis? = null
            val items = try {
                val probe = GenerationAgentClient.probe(this@GeneratedFolderPickerActivity, "閲覧")
                if (probe.code == AgentConnectionClassifier.OK ||
                    probe.code == AgentConnectionClassifier.SD_DOWN
                ) {
                    try {
                        val fetched = GenerationAgentClient.fetchFolders(this@GeneratedFolderPickerActivity)
                        persistRemoteCache(fetched)
                        combineFolders(
                            fetched.map { FolderItem(it.date, it.count, it.thumbnailUrl, remoteDate = it.date) },
                            loadLocalFolders()
                        )
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

            val keepCurrent = items.isEmpty() && (recyclerView.adapter?.itemCount ?: 0) > 0
            if (!keepCurrent) {
                recyclerView.adapter = FolderAdapter(items, ::onFolderSelected)
                if (items.isEmpty()) {
                    emptyState.visibility = View.VISIBLE
                    emptyMessage.text = if (remoteFailure != null) {
                        "PCから日付フォルダを取れなかった。\n[${remoteFailure.code}] ${remoteFailure.title}"
                    } else {
                        "まだ画像が生成されていません。\nPC生成エージェントの接続を確認してください。"
                    }
                } else {
                    emptyState.visibility = View.GONE
                }
            }
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

    private fun openRemoteFolder(
        date: String,
        allowEmpty: Boolean = false,
        thenShowLivePreview: Boolean = false
    ) {
        setLoading(true)
        lifecycleScope.launch {
            try {
                val images = GenerationAgentClient.fetchImages(this@GeneratedFolderPickerActivity, date)
                if (images.isEmpty() && !allowEmpty) {
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
                        image.randomEnabledCategories,
                        image.seed,
                        image.negativePrompt
                    )
                }
                openAlbum(
                    albumName = "生成: $date",
                    uris = images.map { it.url },
                    thumbnailUris = images.map { it.thumbnailUrl },
                    tagLists = images.map { GeneratedImageTagBinding.encodeTagList(it.tags) },
                    remoteDate = date,
                    thenShowLivePreview = thenShowLivePreview
                )
            } catch (error: Exception) {
                if (allowEmpty) {
                    openAlbum(
                        albumName = "生成: $date",
                        uris = emptyList(),
                        remoteDate = date,
                        thenShowLivePreview = thenShowLivePreview
                    )
                    return@launch
                }
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
        folderUri: String? = null,
        thenShowLivePreview: Boolean = false
    ) {
        awaitingAlbumReturn = true
        setLoading(false)
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
        if (thenShowLivePreview) {
            startActivity(Intent(this, GenerationLivePreviewActivity::class.java))
        }
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
            holder.name.text = GeneratedFolderPickerPolicy.formatFolderLabel(folder.name)
            holder.count.text = GeneratedFolderPickerPolicy.countLabel(folder.count)
            if (folder.thumbnail != null) {
                val uri = Uri.parse(folder.thumbnail.toString())
                Glide.with(holder.thumbnail)
                    .load(folder.thumbnail)
                    .override(
                        ImageMemoryPressurePolicy.GRID_THUMB_WIDTH,
                        ImageMemoryPressurePolicy.GRID_THUMB_HEIGHT
                    )
                    .diskCacheStrategy(ImageStoragePolicy.glideDiskCache(uri))
                    .centerCrop()
                    .into(holder.thumbnail)
                ImageMemoryGovernor.onGridBind(holder.thumbnail.context)
            } else {
                Glide.with(holder.thumbnail).clear(holder.thumbnail)
                holder.thumbnail.setImageResource(R.drawable.ic_md3_gallery)
                holder.thumbnail.scaleType = ImageView.ScaleType.CENTER_INSIDE
            }
            holder.card.setOnClickListener { onClick(folder) }
        }

        override fun onViewRecycled(holder: ViewHolder) {
            Glide.with(holder.thumbnail).clear(holder.thumbnail)
            super.onViewRecycled(holder)
        }

        override fun getItemCount(): Int = folders.size
    }
}
