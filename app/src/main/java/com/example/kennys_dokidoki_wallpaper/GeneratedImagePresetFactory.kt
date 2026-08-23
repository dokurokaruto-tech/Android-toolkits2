package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

/**
 * 閲覧中の生成画像からプリセットを保存する。
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
        if (!source.hasRandomizerChoice) {
            commit(context, source, GeneratedImagePresetPolicy.FromImageMode.INDIVIDUAL_CARDS)
            return true
        }
        val randomCount = source.randomPickedIds.size
        val categories = source.randomEnabledCategories.joinToString("、").ifEmpty { "個別ランダマイザー" }
        AlertDialog.Builder(context, R.style.Theme_Kennys_dokidoki_wallpaper)
            .setTitle(PresetSavePolicy.FROM_IMAGE_MENU_LABEL)
            .setMessage("ランダマイザー（$categories）で当たったカードが ${randomCount} 枚ある。")
            .setPositiveButton(PresetSavePolicy.FROM_IMAGE_INDIVIDUAL_LABEL) { _, _ ->
                commit(context, source, GeneratedImagePresetPolicy.FromImageMode.INDIVIDUAL_CARDS)
            }
            .setNeutralButton(PresetSavePolicy.FROM_IMAGE_RANDOMIZER_LABEL) { _, _ ->
                commit(context, source, GeneratedImagePresetPolicy.FromImageMode.KEEP_RANDOMIZER)
            }
            .setNegativeButton("キャンセル", null)
            .show()
        return true
    }

    private fun commit(
        context: Context,
        source: GeneratedImagePresetPolicy.Source,
        mode: GeneratedImagePresetPolicy.FromImageMode
    ) {
        val preset = GeneratedImagePresetPolicy.buildPreset(source, mode)
        PresetManager.addPreset(context, preset)
        val extra = if (mode == GeneratedImagePresetPolicy.FromImageMode.KEEP_RANDOMIZER) {
            "ランダマイザー ${preset.randomEnabledCategories.size} 件"
        } else {
            "カード ${preset.activePromptStates.size} 枚"
        }
        Toast.makeText(
            context,
            "クイックプリセットへ『${preset.name}』を保存した（$extra）。",
            Toast.LENGTH_SHORT
        ).show()
    }
}
