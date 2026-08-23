package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import android.net.Uri
import android.widget.Toast

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
            GeneratedImagePresetPolicy.InferableCard(it.id, it.appliedTags.toSet())
        }
        val source = GeneratedImagePresetPolicy.sourceFrom(
            storedCards = draft?.cardStates.orEmpty(),
            imageTags = imageTags,
            roster = roster,
            width = draft?.width,
            height = draft?.height,
            steps = draft?.steps,
            sampler = draft?.sampler,
            thumbnail = (thumbnailUri ?: uri).toString()
        )
        if (source == null) {
            Toast.makeText(context, "この画像に使われたカードが分からない。新しく生成した画像なら残る。", Toast.LENGTH_LONG).show()
            return false
        }
        val preset = GeneratedImagePresetPolicy.buildPreset(source)
        PresetManager.addPreset(context, preset)
        Toast.makeText(
            context,
            "クイックプリセットへ『${preset.name}』を保存した（${preset.activePromptStates.size}枚）。",
            Toast.LENGTH_SHORT
        ).show()
        return true
    }
}
