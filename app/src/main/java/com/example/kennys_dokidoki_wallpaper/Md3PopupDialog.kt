package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * プロンプトカード／プリセット編集を、画面いっぱいにせず
 * Material Design 3 の浮きポップアップとして出す。
 */
object Md3PopupDialog {
    const val WIDTH_FRACTION = 0.92f
    const val MAX_HEIGHT_FRACTION = 0.86f

    fun wrap(context: Context): ContextThemeWrapper =
        ContextThemeWrapper(context, com.google.android.material.R.style.Theme_Material3_Dark_NoActionBar)

    fun inflate(context: Context, layoutId: Int): Pair<ContextThemeWrapper, View> {
        val md3 = wrap(context)
        return md3 to LayoutInflater.from(md3).inflate(layoutId, null)
    }

    fun popupWidth(screenWidthPx: Int): Int =
        (screenWidthPx * WIDTH_FRACTION).toInt().coerceAtLeast(1)

    fun popupHeight(screenHeightPx: Int, contentHeightPx: Int): Int {
        val cap = (screenHeightPx * MAX_HEIGHT_FRACTION).toInt().coerceAtLeast(1)
        return if (contentHeightPx > 0) contentHeightPx.coerceAtMost(cap) else cap
    }

    fun show(context: Context, view: View): AlertDialog {
        val md3 = view.context
        val dialog = MaterialAlertDialogBuilder(
            md3,
            com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog
        ).setView(view).create()
        dialog.show()
        val metrics = context.resources.displayMetrics
        val width = popupWidth(metrics.widthPixels)
        val maxHeight = popupHeight(metrics.heightPixels, 0)
        dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.window?.setDimAmount(0.6f)
        view.post {
            val height = popupHeight(metrics.heightPixels, view.measuredHeight)
            dialog.window?.setLayout(width, height.coerceAtMost(maxHeight))
        }
        return dialog
    }
}
