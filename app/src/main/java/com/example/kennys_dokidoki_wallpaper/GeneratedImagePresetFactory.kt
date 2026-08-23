package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import android.net.Uri
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textview.MaterialTextView

/**
 * 閲覧中の生成画像からプリセットを保存する。
 * Material Design 3 のフローティングダイアログで名前・分類・ランダマイザー扱いを選ぶ。
 */
object GeneratedImagePresetFactory {
    fun saveFromImage(context: Context, uri: Uri, thumbnailUri: Uri? = null): Boolean {
        DataManager.loadData(context)
        PromptCardManager.loadCards(context)
        PresetManager.loadPresets(context)
        val draft = GeneratedImageDraftStore.get(context, GeneratedImageDraftStore.keyFor(uri))
        val imageTags = draft?.tags
            ?: DataManager.allImages.find { it.uri.toString() == uri.toString() }?.tags
            ?: emptySet()
        val roster = PromptCardManager.promptCards.map {
            GeneratedImagePresetPolicy.InferableCard(it.id, it.appliedTags.toSet(), it.mainPrompt)
        }
        val source = GeneratedImagePresetPolicy.sourceFrom(
            storedCards = draft?.cardStates.orEmpty(),
            imageTags = imageTags,
            roster = roster,
            width = draft?.width,
            height = draft?.height,
            steps = draft?.steps,
            sampler = draft?.sampler,
            thumbnail = (thumbnailUri ?: uri).toString(),
            prompt = draft?.prompt,
            randomPickedIds = draft?.randomPickedIds.orEmpty(),
            randomEnabledCategories = draft?.randomEnabledCategories.orEmpty()
        )
        if (source == null) {
            Toast.makeText(context, "この画像に使われたカードが分からない。新しく生成した画像なら残る。", Toast.LENGTH_LONG).show()
            return false
        }
        showSaveDialog(context, source)
        return true
    }

    private fun showSaveDialog(context: Context, source: GeneratedImagePresetPolicy.Source) {
        val md3 = ContextThemeWrapper(
            context,
            com.google.android.material.R.style.Theme_Material3_Dark_NoActionBar
        )
        val view = LayoutInflater.from(md3).inflate(R.layout.dialog_preset_from_image, null)
        val summary = view.findViewById<MaterialTextView>(R.id.tv_preset_from_image_summary)
        val nameField = view.findViewById<TextInputEditText>(R.id.et_preset_from_image_name)
        val categoryField = view.findViewById<AutoCompleteTextView>(R.id.act_preset_from_image_category)
        val modes = view.findViewById<View>(R.id.ll_preset_from_image_modes)
        val cardIndividual = view.findViewById<MaterialCardView>(R.id.card_preset_mode_individual)
        val cardRandomizer = view.findViewById<MaterialCardView>(R.id.card_preset_mode_randomizer)
        val individualTitle = view.findViewById<MaterialTextView>(R.id.tv_preset_mode_individual_title)
        val individualBody = view.findViewById<MaterialTextView>(R.id.tv_preset_mode_individual_body)
        val randomizerTitle = view.findViewById<MaterialTextView>(R.id.tv_preset_mode_randomizer_title)
        val randomizerBody = view.findViewById<MaterialTextView>(R.id.tv_preset_mode_randomizer_body)
        val cancel = view.findViewById<MaterialButton>(R.id.btn_preset_from_image_cancel)
        val save = view.findViewById<MaterialButton>(R.id.btn_preset_from_image_save)

        summary.text = GeneratedImagePresetPolicy.dialogSummary(source)
        nameField.setText(PresetSavePolicy.defaultName(source.width, source.height))
        val categories = PresetSavePolicy.selectableCategories(PresetManager.categoryOrder)
        categoryField.setAdapter(ArrayAdapter(md3, android.R.layout.simple_list_item_1, categories))
        categoryField.setText(PresetSavePolicy.defaultCategory(categories), false)
        categoryField.keyListener = null
        categoryField.setOnClickListener { categoryField.showDropDown() }

        individualTitle.text = PresetSavePolicy.FROM_IMAGE_INDIVIDUAL_LABEL
        individualBody.text = PresetSavePolicy.FROM_IMAGE_INDIVIDUAL_DETAIL
        randomizerTitle.text = PresetSavePolicy.FROM_IMAGE_RANDOMIZER_LABEL
        randomizerBody.text = PresetSavePolicy.FROM_IMAGE_RANDOMIZER_DETAIL

        var mode = GeneratedImagePresetPolicy.FromImageMode.INDIVIDUAL_CARDS
        fun paintMode(selected: GeneratedImagePresetPolicy.FromImageMode) {
            mode = selected
            paintChoiceCard(
                card = cardIndividual,
                title = individualTitle,
                body = individualBody,
                selected = selected == GeneratedImagePresetPolicy.FromImageMode.INDIVIDUAL_CARDS
            )
            paintChoiceCard(
                card = cardRandomizer,
                title = randomizerTitle,
                body = randomizerBody,
                selected = selected == GeneratedImagePresetPolicy.FromImageMode.KEEP_RANDOMIZER
            )
        }
        if (source.hasRandomizerChoice) {
            modes.visibility = View.VISIBLE
            paintMode(GeneratedImagePresetPolicy.FromImageMode.INDIVIDUAL_CARDS)
            cardIndividual.setOnClickListener {
                paintMode(GeneratedImagePresetPolicy.FromImageMode.INDIVIDUAL_CARDS)
            }
            cardRandomizer.setOnClickListener {
                paintMode(GeneratedImagePresetPolicy.FromImageMode.KEEP_RANDOMIZER)
            }
        } else {
            modes.visibility = View.GONE
        }

        val dialog = MaterialAlertDialogBuilder(
            md3,
            com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog
        ).setView(view).create()
        cancel.setOnClickListener { dialog.dismiss() }
        save.setOnClickListener {
            val name = nameField.text?.toString()?.trim().orEmpty()
            if (name.isEmpty()) {
                nameField.error = "名称を入力してください"
                nameField.requestFocus()
                return@setOnClickListener
            }
            commit(
                context = context,
                source = source,
                mode = mode,
                name = name,
                category = categoryField.text?.toString()
            )
            dialog.dismiss()
        }
        dialog.show()
        dialog.window?.setLayout(
            (context.resources.displayMetrics.widthPixels * 0.92f).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog.window?.setDimAmount(0.6f)
    }

    private fun paintChoiceCard(
        card: MaterialCardView,
        title: MaterialTextView,
        body: MaterialTextView,
        selected: Boolean
    ) {
        val density = card.resources.displayMetrics.density
        card.isChecked = selected
        card.strokeWidth = ((if (selected) 2f else 1f) * density).toInt()
        card.strokeColor = MaterialColors.getColor(
            card,
            if (selected) com.google.android.material.R.attr.colorPrimary
            else com.google.android.material.R.attr.colorOutlineVariant
        )
        card.setCardBackgroundColor(
            MaterialColors.getColor(
                card,
                if (selected) com.google.android.material.R.attr.colorSecondaryContainer
                else com.google.android.material.R.attr.colorSurfaceContainerHigh
            )
        )
        val onColor = MaterialColors.getColor(
            card,
            if (selected) com.google.android.material.R.attr.colorOnSecondaryContainer
            else com.google.android.material.R.attr.colorOnSurface
        )
        val muted = MaterialColors.getColor(
            card,
            if (selected) com.google.android.material.R.attr.colorOnSecondaryContainer
            else com.google.android.material.R.attr.colorOnSurfaceVariant
        )
        title.setTextColor(onColor)
        body.setTextColor(muted)
    }

    private fun commit(
        context: Context,
        source: GeneratedImagePresetPolicy.Source,
        mode: GeneratedImagePresetPolicy.FromImageMode,
        name: String?,
        category: String?
    ) {
        val preset = GeneratedImagePresetPolicy.buildPreset(
            source = source,
            mode = mode,
            name = name,
            category = category
        )
        PresetManager.addPreset(context, preset)
        val extra = if (mode == GeneratedImagePresetPolicy.FromImageMode.KEEP_RANDOMIZER) {
            "ランダマイザー ${preset.randomEnabledCategories.size} 件"
        } else {
            "カード ${preset.activePromptStates.size} 枚"
        }
        Toast.makeText(
            context,
            "『${preset.category}』へ『${preset.name}』を保存した（$extra）。",
            Toast.LENGTH_SHORT
        ).show()
    }
}
