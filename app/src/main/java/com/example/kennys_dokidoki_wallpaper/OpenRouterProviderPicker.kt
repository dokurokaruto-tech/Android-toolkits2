package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatDialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

internal object OpenRouterProviderPicker {
    fun show(
        context: Context,
        scope: CoroutineScope,
        model: ModelMd3Item,
        onSelect: (OpenRouterEndpoint?) -> Unit
    ): AppCompatDialog {
        val (_, view) = Md3PopupDialog.inflate(context, R.layout.dialog_openrouter_providers)
        val list = view.findViewById<RecyclerView>(R.id.rv_providers)
        val status = view.findViewById<TextView>(R.id.tv_provider_status)
        val progress = view.findViewById<LinearProgressIndicator>(R.id.progress_providers)
        val retry = view.findViewById<MaterialButton>(R.id.btn_provider_retry)
        val auto = view.findViewById<MaterialButton>(R.id.btn_provider_auto)
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val selectedTag = OpenRouterRouting.selected(prefs, model.id)
        view.findViewById<TextView>(R.id.tv_provider_model).text = model.name
        list.layoutManager = LinearLayoutManager(view.context)
        if (prefs.getString("chat_openrouter_model", null) == model.id && selectedTag == null) {
            auto.setText(R.string.or_provider_auto_active)
        }

        val dialog = Md3PopupDialog.show(context, view)
        var loadJob: Job? = null
        val lifecycle = (context as? LifecycleOwner)?.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) {
                dialog.dismiss()
            }
        }
        lifecycle?.addObserver(observer)
        dialog.setOnDismissListener {
            loadJob?.cancel()
            lifecycle?.removeObserver(observer)
        }

        fun select(endpoint: OpenRouterEndpoint?) {
            dialog.dismiss()
            onSelect(endpoint)
        }

        fun load() {
            loadJob?.cancel()
            list.adapter = null
            status.setText(R.string.or_provider_loading)
            progress.show()
            retry.isEnabled = false
            loadJob = scope.launch {
                try {
                    val endpoints = OpenRouterProviders.load(context.applicationContext, model.id)
                    if (!dialog.isShowing) {
                        return@launch
                    }
                    status.text = if (endpoints.isEmpty()) {
                        context.getString(R.string.or_provider_empty)
                    } else {
                        context.getString(
                            R.string.or_provider_loaded, endpoints.size,
                            DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())
                        )
                    }
                    list.adapter = OpenRouterProviderAdapter(endpoints, selectedTag) { select(it) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    if (dialog.isShowing) {
                        status.setText(R.string.or_provider_error)
                    }
                } finally {
                    if (dialog.isShowing) {
                        progress.hide()
                        retry.isEnabled = true
                    }
                }
            }
        }

        retry.setOnClickListener { load() }
        auto.setOnClickListener { select(null) }
        view.findViewById<View>(R.id.btn_provider_back).setOnClickListener { dialog.dismiss() }
        load()
        return dialog
    }
}
