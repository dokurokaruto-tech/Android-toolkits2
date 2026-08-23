package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import android.net.Uri

/**
 * 完成したサムネイルURLをカード／プリセットへ載せる。
 * 対象がまだ無いときは pending に残し、保存時に回収する。
 */
object ThumbnailBinder {
    fun interface Listener {
        fun onBound(target: ThumbnailBindPolicy.Target, uri: Uri)
    }

    private val listeners = mutableListOf<Listener>()

    @Synchronized
    fun addListener(listener: Listener) {
        if (listener !in listeners) listeners.add(listener)
    }

    @Synchronized
    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    private fun notifyBound(target: ThumbnailBindPolicy.Target, uri: Uri) {
        val deliver = Runnable {
            synchronized(this) { listeners.toList() }.forEach { it.onBound(target, uri) }
        }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            deliver.run()
        } else {
            android.os.Handler(android.os.Looper.getMainLooper()).post(deliver)
        }
    }

    fun applyCompleted(context: Context, urls: List<String>, targets: List<ThumbnailBindPolicy.Target>): Int {
        if (urls.isEmpty() || targets.isEmpty()) return 0
        PromptCardManager.loadCards(context)
        PresetManager.loadPresets(context)
        var bound = 0
        var cardsDirty = false
        var presetsDirty = false
        ThumbnailBindPolicy.pairUrls(urls, targets).forEach { (target, url) ->
            val uri = Uri.parse(url)
            when (applyLoaded(context, target, uri)) {
                ApplyResult.CARD -> {
                    cardsDirty = true
                    bound++
                    notifyBound(target, uri)
                }
                ApplyResult.PRESET -> {
                    presetsDirty = true
                    bound++
                    notifyBound(target, uri)
                }
                ApplyResult.PENDING -> {
                    bound++
                    notifyBound(target, uri)
                }
                ApplyResult.UNCHANGED -> notifyBound(target, uri)
            }
        }
        if (cardsDirty) PromptCardManager.saveCards(context)
        if (presetsDirty) PresetManager.savePresets(context)
        return bound
    }

    fun applyOne(context: Context, target: ThumbnailBindPolicy.Target, uri: Uri): Boolean {
        PromptCardManager.loadCards(context)
        PresetManager.loadPresets(context)
        return when (applyLoaded(context, target, uri)) {
            ApplyResult.CARD -> {
                PromptCardManager.saveCards(context)
                notifyBound(target, uri)
                true
            }
            ApplyResult.PRESET -> {
                PresetManager.savePresets(context)
                notifyBound(target, uri)
                true
            }
            ApplyResult.PENDING, ApplyResult.UNCHANGED -> {
                notifyBound(target, uri)
                true
            }
        }
    }

    fun resolveForSave(context: Context, kind: String, id: String, fallback: Uri?): Uri? {
        val target = ThumbnailBindPolicy.parseTarget(kind, id) ?: return fallback
        return fallback ?: ThumbnailBindStore.consumePending(context, target)
    }

    private enum class ApplyResult { CARD, PRESET, PENDING, UNCHANGED }

    private fun applyLoaded(
        context: Context,
        target: ThumbnailBindPolicy.Target,
        uri: Uri
    ): ApplyResult {
        if (!target.isValid || uri.toString().isBlank()) return ApplyResult.UNCHANGED
        return when (target.kind) {
            ThumbnailBindPolicy.KIND_CARD -> {
                val card = PromptCardManager.promptCards.find { it.id == target.id }
                if (card == null) {
                    ThumbnailBindStore.putPending(context, target, uri)
                    ApplyResult.PENDING
                } else if (card.thumbnailUri?.toString() == uri.toString()) {
                    ThumbnailBindStore.consumePending(context, target)
                    ApplyResult.UNCHANGED
                } else {
                    card.thumbnailUri = uri
                    ThumbnailBindStore.consumePending(context, target)
                    ApplyResult.CARD
                }
            }
            ThumbnailBindPolicy.KIND_PRESET -> {
                val preset = PresetManager.presets.find { it.id == target.id }
                if (preset == null) {
                    ThumbnailBindStore.putPending(context, target, uri)
                    ApplyResult.PENDING
                } else if (preset.thumbnailUri?.toString() == uri.toString()) {
                    ThumbnailBindStore.consumePending(context, target)
                    ApplyResult.UNCHANGED
                } else {
                    preset.thumbnailUri = uri
                    ThumbnailBindStore.consumePending(context, target)
                    ApplyResult.PRESET
                }
            }
            else -> ApplyResult.UNCHANGED
        }
    }
}
