package com.example.kennys_dokidoki_wallpaper

import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch

/** Material Design 3 UI used from the prompt-card editor. */
class PromptCardAiDialog(private val activity: AppCompatActivity) {
    fun show(
        existingMain: String,
        existingNegative: String,
        onConverted: (PromptCardAiResult) -> Unit
    ) {
        val md3 = ContextThemeWrapper(activity, com.google.android.material.R.style.Theme_Material3_Dark_NoActionBar)
        val view = LayoutInflater.from(md3).inflate(R.layout.dialog_prompt_card_ai, null)
        val instruction = view.findViewById<TextInputEditText>(R.id.et_natural_language)
        val modelCard = view.findViewById<MaterialCardView>(R.id.card_prompt_ai_model)
        val modelText = view.findViewById<TextView>(R.id.tv_prompt_ai_model)
        val useExisting = view.findViewById<MaterialSwitch>(R.id.switch_use_existing_prompt)
        val errorText = view.findViewById<TextView>(R.id.tv_prompt_ai_error)
        val progress = view.findViewById<LinearProgressIndicator>(R.id.progress_prompt_ai)
        val cancel = view.findViewById<MaterialButton>(R.id.btn_prompt_ai_cancel)
        val generate = view.findViewById<MaterialButton>(R.id.btn_prompt_ai_generate)

        var choice = PromptCardAiGenerator.selectedChoice(activity)
        fun renderChoice() {
            modelText.text = choice.displayName
        }
        renderChoice()
        useExisting.isChecked = existingMain.isNotBlank() || existingNegative.isNotBlank()

        val dialog = MaterialAlertDialogBuilder(md3).setView(view).create()
        modelCard.setOnClickListener {
            showModelPicker(choice) { selected ->
                choice = selected
                PromptCardAiGenerator.saveSelectedChoice(activity, selected)
                renderChoice()
            }
        }
        cancel.setOnClickListener { dialog.dismiss() }
        generate.setOnClickListener {
            val naturalText = instruction.text?.toString()?.trim().orEmpty()
            if (naturalText.isEmpty()) {
                instruction.error = "作りたい画像の説明を入力してください"
                return@setOnClickListener
            }
            errorText.visibility = View.GONE
            progress.show()
            generate.isEnabled = false
            modelCard.isEnabled = false
            activity.lifecycleScope.launch {
                runCatching {
                    PromptCardAiGenerator.generate(
                        context = activity,
                        choice = choice,
                        naturalLanguage = naturalText,
                        existingMain = existingMain,
                        existingNegative = existingNegative,
                        useExisting = useExisting.isChecked
                    )
                }.onSuccess { result ->
                    onConverted(result)
                    Toast.makeText(activity, "プロンプトへ変換しました", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                }.onFailure { error ->
                    progress.hide()
                    generate.isEnabled = true
                    modelCard.isEnabled = true
                    errorText.text = error.message ?: "変換に失敗しました"
                    errorText.visibility = View.VISIBLE
                }
            }
        }

        dialog.show()
        dialog.window?.setLayout(
            (activity.resources.displayMetrics.widthPixels * 0.94f).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun showModelPicker(
        current: PromptCardLlmChoice,
        onSelected: (PromptCardLlmChoice) -> Unit
    ) {
        val md3 = ContextThemeWrapper(activity, com.google.android.material.R.style.Theme_Material3_Dark_NoActionBar)
        val view = LayoutInflater.from(md3).inflate(R.layout.dialog_model_picker_md3, null)
        val title = view.findViewById<TextView>(R.id.tv_picker_title)
        val provider = view.findViewById<TextView>(R.id.tv_provider)
        val recycler = view.findViewById<RecyclerView>(R.id.rv_models)
        val progress = view.findViewById<CircularProgressIndicator>(R.id.progress_models)
        val empty = view.findViewById<TextView>(R.id.tv_empty)
        val count = view.findViewById<TextView>(R.id.tv_count)
        val chipAll = view.findViewById<Chip>(R.id.chip_all)
        val chipFree = view.findViewById<Chip>(R.id.chip_free)
        val sort = view.findViewById<MaterialButton>(R.id.btn_sort)
        val refresh = view.findViewById<MaterialButton>(R.id.btn_refresh)

        title.text = "プロンプト変換LLM"
        provider.text = "xAI / OpenRouter / ローカル"
        recycler.layoutManager = LinearLayoutManager(md3)
        val dialog = MaterialAlertDialogBuilder(md3).setView(view).create()

        var choices = PromptCardAiGenerator.loadCachedChoices(activity)
        var freeOnly = false
        var sortByName = false
        var loading = false

        fun render() {
            if (loading) {
                recycler.visibility = View.GONE
                empty.visibility = View.GONE
                return
            }
            val filtered = if (freeOnly) choices.filter { it.isFree } else choices
            val sorted = if (sortByName) filtered.sortedBy { it.displayName.lowercase() } else filtered
            count.text = "${sorted.size} 個のLLM"
            sort.text = if (sortByName) "登録順" else "名前順"
            if (sorted.isEmpty()) {
                recycler.visibility = View.GONE
                empty.visibility = View.VISIBLE
            } else {
                empty.visibility = View.GONE
                recycler.visibility = View.VISIBLE
                recycler.adapter = ModelMd3Adapter(
                    items = sorted.map {
                        ModelMd3Item(
                            id = it.key,
                            name = it.displayName,
                            contextLength = it.contextLength,
                            isFree = it.isFree,
                            pricePerMillion = it.pricePerMillion
                        )
                    },
                    selectedId = current.key
                ) { selectedItem ->
                    val selected = sorted.first { it.key == selectedItem.id }
                    onSelected(selected)
                    dialog.dismiss()
                }
            }
        }

        fun setLoading(value: Boolean) {
            loading = value
            if (value) progress.show() else progress.hide()
            render()
        }

        val filterClick = View.OnClickListener {
            freeOnly = chipFree.isChecked
            render()
        }
        chipAll.setOnClickListener(filterClick)
        chipFree.setOnClickListener(filterClick)
        sort.setOnClickListener {
            sortByName = !sortByName
            render()
        }
        refresh.setOnClickListener {
            setLoading(true)
            activity.lifecycleScope.launch {
                runCatching { PromptCardAiGenerator.refreshOpenRouterChoices(activity) }
                    .onSuccess { choices = it }
                    .onFailure {
                        Toast.makeText(activity, "モデル一覧を更新できませんでした", Toast.LENGTH_SHORT).show()
                    }
                if (dialog.isShowing) setLoading(false)
            }
        }

        render()
        dialog.show()
        dialog.window?.setLayout(
            (activity.resources.displayMetrics.widthPixels * 0.95f).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        (recycler.layoutParams as? FrameLayout.LayoutParams)?.let { params ->
            params.height = (activity.resources.displayMetrics.heightPixels * 0.58f).toInt()
            recycler.layoutParams = params
        }
    }
}
