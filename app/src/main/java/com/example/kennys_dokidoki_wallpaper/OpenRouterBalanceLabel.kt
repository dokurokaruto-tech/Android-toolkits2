package com.example.kennys_dokidoki_wallpaper

import android.view.View
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 3つのAI画面で、表示・取消・復帰時の更新を共通化する。 */
internal class OpenRouterBalanceLabel(
    private val view: TextView,
    private val owner: LifecycleOwner,
    private val selectedModel: () -> String?
) {
    private companion object {
        const val REFRESH_DELAY_MS = 150L
    }

    private var job: Job? = null
    private var closed = false
    private val observer = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_RESUME -> refresh()
            Lifecycle.Event.ON_STOP -> job?.cancel()
            Lifecycle.Event.ON_DESTROY -> close()
            else -> Unit
        }
    }

    init {
        owner.lifecycle.addObserver(observer)
    }

    fun refresh() {
        if (closed) {
            return
        }
        job?.cancel()
        val model = selectedModel()
        if (model == null) {
            view.visibility = View.GONE
            view.contentDescription = null
            return
        }
        val context = view.context.applicationContext
        view.visibility = View.VISIBLE
        showText(OpenRouterBalance.initialLine(context, model))
        job = owner.lifecycleScope.launch {
            try {
                // 設定の連続保存で残高APIへのリクエストが重複するのを避ける。
                delay(REFRESH_DELAY_MS)
                showText(OpenRouterBalance.load(context, model))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                showText(OpenRouterBalancePolicy.CREDIT_FAILED)
            }
        }
    }

    fun close() {
        closed = true
        job?.cancel()
        owner.lifecycle.removeObserver(observer)
    }

    private fun showText(text: String) {
        view.text = text
        view.contentDescription = "OpenRouter · $text"
    }
}
