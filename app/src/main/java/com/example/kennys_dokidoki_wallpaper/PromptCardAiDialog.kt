package com.example.kennys_dokidoki_wallpaper

import android.content.Context
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
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch

/** タグの置き換え画面と同じ並びにした、プロンプトカード専用のAI画面。 */
class PromptCardAiDialog(private val activity: AppCompatActivity) {
    fun show(
        cardLabel: String,
        existingMain: String,
        existingNegative: String,
        onConverted: (PromptCardAiResult) -> Unit
    ) {
        val (_, view) = Md3PopupDialog.inflate(activity, R.layout.dialog_prompt_card_ai)
        val extra = view.findViewById<TextInputEditText>(R.id.et_natural_language)
        val modelText = view.findViewById<TextView>(R.id.tv_prompt_ai_model)
        val usageText = view.findViewById<TextView>(R.id.tv_prompt_ai_usage)
        val changeModel = view.findViewById<MaterialButton>(R.id.btn_prompt_ai_change_model)
        val useLabel = view.findViewById<MaterialSwitch>(R.id.switch_use_card_label)
        val useExisting = view.findViewById<MaterialSwitch>(R.id.switch_use_existing_prompt)
        val errorText = view.findViewById<TextView>(R.id.tv_prompt_ai_error)
        val progress = view.findViewById<LinearProgressIndicator>(R.id.progress_prompt_ai)
        val cancel = view.findViewById<MaterialButton>(R.id.btn_prompt_ai_cancel)
        val generate = view.findViewById<MaterialButton>(R.id.btn_prompt_ai_generate)
        val chips = view.findViewById<ChipGroup>(R.id.cg_prompt_card_profiles)
        val editPresets = view.findViewById<MaterialButton>(R.id.btn_prompt_ai_edit_presets)
        val deletePreset = view.findViewById<MaterialButton>(R.id.btn_prompt_ai_delete_preset)
        val prefs = activity.getSharedPreferences("settings", Context.MODE_PRIVATE)

        var choice = PromptCardAiGenerator.selectedChoice(activity)
        var selectedPresetName: String? = null
        var selectedSystemPrompt = PromptCardInstructionPolicy.DEFAULT_PROMPT

        fun renderChoice() {
            modelText.text = PromptCardAiCopy.modelLine(choice)
            if (choice.provider == PromptCardLlmProvider.OPENROUTER) {
                val keys = OpenRouterManager.getApiKeys(activity)
                val limit = keys.size * 50
                usageText.text = TagAiGenerateCopy.usageLine(OpenRouterManager.getTotalUsage(activity), limit)
                usageText.visibility = View.VISIBLE
            } else {
                usageText.visibility = View.GONE
            }
        }

        fun bindPresets() {
            val profiles = PromptCardInstructionPolicy.parse(
                prefs.getString(PromptCardInstructionPolicy.PROFILES_KEY, null),
                prefs.getString(PromptCardInstructionPolicy.LEGACY_KEY, null)
            )
            val selected = PromptCardInstructionPolicy.selectedIndex(profiles, selectedPresetName)
            selectedPresetName = profiles[selected].name
            selectedSystemPrompt = profiles[selected].content
            chips.removeAllViews()
            profiles.forEachIndexed { index, profile ->
                val chip = Chip(view.context).apply {
                    text = profile.name
                    isCheckable = true
                    isChecked = index == selected
                    setOnCheckedChangeListener { _, isChecked ->
                        if (isChecked) {
                            selectedPresetName = profile.name
                            selectedSystemPrompt = profile.content
                            deletePreset.isEnabled = PromptCardInstructionPolicy.canDelete(profiles)
                        }
                    }
                }
                chips.addView(chip)
            }
            deletePreset.isEnabled = PromptCardInstructionPolicy.canDelete(profiles)
        }

        renderChoice()
        bindPresets()
        useLabel.isChecked = cardLabel.isNotBlank()
        useExisting.isChecked = existingMain.isNotBlank() || existingNegative.isNotBlank()

        val dialog = Md3PopupDialog.show(activity, view)
        changeModel.setOnClickListener {
            showModelPicker(choice) { selected ->
                choice = selected
                PromptCardAiGenerator.saveSelectedChoice(activity, selected)
                renderChoice()
            }
        }
        editPresets.setOnClickListener {
            PromptCardInstructionEditor.show(activity) { bindPresets() }
        }
        deletePreset.setOnClickListener {
            PromptCardInstructionEditor.delete(activity, selectedPresetName) { bindPresets() }
        }
        cancel.setOnClickListener { dialog.dismiss() }
        generate.setOnClickListener {
            val extraText = extra.text?.toString()?.trim().orEmpty()
            if (extraText.isEmpty() && !useLabel.isChecked && !useExisting.isChecked) {
                extra.error = PromptCardAiCopy.NEED_INPUT
                return@setOnClickListener
            }
            errorText.visibility = View.GONE
            progress.show()
            generate.isEnabled = false
            changeModel.isEnabled = false
            activity.lifecycleScope.launch {
                runCatching {
                    PromptCardAiGenerator.generate(
                        context = activity,
                        choice = choice,
                        naturalLanguage = extraText,
                        cardLabel = cardLabel,
                        existingMain = existingMain,
                        existingNegative = existingNegative,
                        useLabel = useLabel.isChecked,
                        useExisting = useExisting.isChecked,
                        systemPrompt = selectedSystemPrompt
                    )
                }.onSuccess { result ->
                    onConverted(result)
                    Toast.makeText(activity, PromptCardAiCopy.DONE, Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                }.onFailure { error ->
                    progress.hide()
                    generate.isEnabled = true
                    changeModel.isEnabled = true
                    errorText.text = error.message ?: PromptCardAiCopy.FAILED
                    errorText.visibility = View.VISIBLE
                }
            }
        }
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

        title.text = "モデル"
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
