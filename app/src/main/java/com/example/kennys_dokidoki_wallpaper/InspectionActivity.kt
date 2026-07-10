package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import java.util.Locale

class InspectionActivity : AppCompatActivity() {

    private lateinit var imageUris: List<String>
    private lateinit var targetCategories: List<String>
    private var forceInspection: Boolean = false
    private var forceBehaviorMode: Int = 0 // 0: Overwrite, 1: Select, 2: Keep
    
    private var currentImageIndex = 0
    private var currentCategoryIndex = 0
    
    private lateinit var ivImage: ImageView
    private lateinit var tvQuestion: TextView
    private lateinit var tvProgress: TextView
    private lateinit var rvTags: RecyclerView
    private lateinit var btnSort: ImageButton
    private lateinit var vOverlay: View
    
    private var isSortAlpha = false
    private var isOverlayVisible = true
    
    data class InspectionHistory(
        val imageIndex: Int,
        val categoryIndex: Int,
        val addedTag: String?,
        val removedTags: List<String> = emptyList(),
        val parentTagsAffected: List<String> = emptyList()
    )
    private val historyStack = mutableListOf<InspectionHistory>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_inspection)

        imageUris = intent.getStringArrayListExtra("IMAGE_URIS") ?: emptyList()
        targetCategories = intent.getStringArrayListExtra("TARGET_CATEGORIES") ?: emptyList()
        forceInspection = intent.getBooleanExtra("FORCE_INSPECTION", false)
        forceBehaviorMode = intent.getIntExtra("FORCE_BEHAVIOR_MODE", 0)

        if (imageUris.isEmpty() || targetCategories.isEmpty()) {
            finish()
            return
        }

        DataManager.loadData(this)
        TagManager.loadTags(this)
        
        val prefs = getSharedPreferences("tag_display_settings", Context.MODE_PRIVATE)
        isSortAlpha = prefs.getBoolean("global_inspection_sort_alpha", false)

        ivImage = findViewById(R.id.iv_inspection_image)
        tvQuestion = findViewById(R.id.tv_inspection_question)
        tvProgress = findViewById(R.id.tv_inspection_progress)
        rvTags = findViewById(R.id.rv_inspection_tags)
        btnSort = findViewById(R.id.btn_inspection_sort)
        vOverlay = findViewById(R.id.v_overlay)
        
        btnSort.setOnClickListener {
            isSortAlpha = !isSortAlpha
            prefs.edit().putBoolean("global_inspection_sort_alpha", isSortAlpha).apply()
            updateSortIcon()
            showQuestion()
        }
        
        findViewById<View>(R.id.btn_toggle_overlay).setOnClickListener {
            isOverlayVisible = !isOverlayVisible
            vOverlay.visibility = if (isOverlayVisible) View.VISIBLE else View.GONE
        }
        
        updateSortIcon()

        findViewById<View>(R.id.btn_inspection_skip).setOnClickListener {
            historyStack.add(InspectionHistory(currentImageIndex, currentCategoryIndex, null))
            moveToNextQuestion()
        }
        
        findViewById<View>(R.id.btn_inspection_back).setOnClickListener {
            undoLastAction()
        }
        
        findViewById<View>(R.id.btn_inspection_close).setOnClickListener {
            finish()
        }

        startInspection()
    }

    private fun updateSortIcon() {
        if (isSortAlpha) {
            btnSort.setImageResource(android.R.drawable.arrow_up_float)
            btnSort.alpha = 1.0f
        } else {
            btnSort.setImageResource(android.R.drawable.stat_notify_sync)
            btnSort.alpha = 0.4f
        }
    }

    private fun startInspection() {
        showQuestion()
    }

    private fun showQuestion() {
        if (currentImageIndex >= imageUris.size) {
            Toast.makeText(this, "全ての検査が完了しました。", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val uriStr = imageUris[currentImageIndex]
        val entry = DataManager.allImages.find { it.uri.toString() == uriStr }
        
        if (entry == null) {
            currentImageIndex++
            currentCategoryIndex = 0
            showQuestion()
            return
        }

        if (currentCategoryIndex >= targetCategories.size) {
            currentImageIndex++
            currentCategoryIndex = 0
            showQuestion()
            return
        }

        val categoryName = targetCategories[currentCategoryIndex]
        val category = TagManager.categories.find { it.name == categoryName }
        
        if (category == null) {
            currentCategoryIndex++
            showQuestion()
            return
        }

        if (!forceInspection) {
            val effectiveTags = TagManager.getEffectiveTags(entry.tags)
            val hasTagFromCategory = effectiveTags.any { it in category.tags }
            if (hasTagFromCategory) {
                currentCategoryIndex++
                showQuestion()
                return
            }
        }

        Glide.with(this)
            .load(entry.uri)
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .into(ivImage)

        tvQuestion.text = String.format(Locale.getDefault(), "この画像の %s は？", categoryName)
        tvProgress.text = String.format(Locale.getDefault(), "%d / %d 枚目", currentImageIndex + 1, imageUris.size)

        setupTagList(category, entry)
    }

    private fun setupTagList(category: TagCategory, entry: ImageEntry) {
        val displayTags = if (isSortAlpha) {
            category.tags.sortedBy { it.lowercase() }
        } else {
            category.tags
        }

        rvTags.layoutManager = GridLayoutManager(this, 3)
        rvTags.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val view = LayoutInflater.from(parent.context).inflate(R.layout.item_tag_picker_chip, parent, false)
                return object : RecyclerView.ViewHolder(view) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val tag = displayTags[position]
                val tv = holder.itemView.findViewById<TextView>(R.id.chip_tag)
                tv.text = tag
                
                // すでに持っているタグなら色を変える
                if (entry.tags.contains(tag)) {
                    tv.setBackgroundResource(R.drawable.bg_persona_item_selected)
                    tv.setTextColor(Color.BLACK)
                } else {
                    tv.setBackgroundResource(R.drawable.bg_persona_item)
                    tv.setTextColor(Color.parseColor("#00F0FF"))
                }

                holder.itemView.setOnClickListener {
                    handleTagSelection(entry, category, tag)
                }
            }

            override fun getItemCount() = displayTags.size
        }
    }

    private fun handleTagSelection(entry: ImageEntry, category: TagCategory, tag: String) {
        val existingTagsInCategory = entry.tags.filter { it in category.tags }
        
        when (forceBehaviorMode) {
            0 -> { // Overwrite
                applyTagWithRemoval(entry, category, tag, existingTagsInCategory)
            }
            1 -> { // Select
                if (existingTagsInCategory.isNotEmpty()) {
                    showTagRemovalDialog(entry, category, tag, existingTagsInCategory)
                } else {
                    applyTagWithRemoval(entry, category, tag, emptyList())
                }
            }
            2 -> { // Keep
                applyTagWithRemoval(entry, category, tag, emptyList())
            }
        }
    }

    private fun showTagRemovalDialog(entry: ImageEntry, category: TagCategory, newTag: String, existing: List<String>) {
        val tagNames = existing.toTypedArray()
        val checkedItems = BooleanArray(tagNames.size) { true }
        
        AlertDialog.Builder(this)
            .setTitle("既存のタグをどうする？")
            .setMultiChoiceItems(tagNames, checkedItems) { _, which, isChecked ->
                checkedItems[which] = isChecked
            }
            .setPositiveButton("決定") { _, _ ->
                val toRemove = mutableListOf<String>()
                for (i in checkedItems.indices) {
                    if (checkedItems[i]) toRemove.add(tagNames[i])
                }
                applyTagWithRemoval(entry, category, newTag, toRemove)
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun applyTagWithRemoval(entry: ImageEntry, category: TagCategory, newTag: String, toRemove: List<String>) {
        val affectedParents = mutableListOf<String>()
        val parentCatName = category.parentCategoryName
        if (parentCatName != null) {
            entry.tags.forEach { t ->
                if (TagManager.categories.find { it.name == parentCatName }?.tags?.contains(t) == true) {
                    affectedParents.add(t)
                }
            }
        }

        // 履歴に追加
        historyStack.add(InspectionHistory(currentImageIndex, currentCategoryIndex, newTag, toRemove, affectedParents))

        // 削除実行
        toRemove.forEach { entry.tags.remove(it) }
        
        // 追加実行
        entry.tags.add(newTag)

        // 統一規格の自動登録
        TagManager.autoRegisterUniformImpliedTags(this, entry.tags, setOf(newTag))
        
        DataManager.saveData(this)
        moveToNextQuestion()
    }

    private fun moveToNextQuestion() {
        currentCategoryIndex++
        showQuestion()
    }

    private fun undoLastAction() {
        if (historyStack.isEmpty()) {
            Toast.makeText(this, "これ以上戻れないわ", Toast.LENGTH_SHORT).show()
            return
        }

        val lastAction = historyStack.removeAt(historyStack.size - 1)
        val entry = DataManager.allImages.find { it.uri.toString() == imageUris[lastAction.imageIndex] }

        if (entry != null) {
            if (lastAction.addedTag != null) {
                entry.tags.remove(lastAction.addedTag)
                
                lastAction.parentTagsAffected.forEach { parentTag ->
                    val currentImplied = TagManager.getImpliedTags(parentTag).toMutableSet()
                    currentImplied.remove(lastAction.addedTag)
                    TagManager.setImpliedTags(this, parentTag, currentImplied)
                }
            }
            
            // 削除されたタグを戻す
            lastAction.removedTags.forEach { entry.tags.add(it) }
            
            DataManager.saveData(this)
        }

        currentImageIndex = lastAction.imageIndex
        currentCategoryIndex = lastAction.categoryIndex
        showQuestion()
    }
}
