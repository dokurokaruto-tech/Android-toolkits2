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
 * 中身の量に関わらず同じ縦幅にし、できるだけ多くの項目を見せる。
 */
object Md3PopupDialog {
    const val WIDTH_FRACTION = 0.92f
    const val HEIGHT_FRACTION = 0.92f

    fun wrap(context: Context): ContextThemeWrapper =
        ContextThemeWrapper(context, com.google.android.material.R.style.Theme_Material3_Dark_NoActionBar)

    fun inflate(context: Context, layoutId: Int): Pair<ContextThemeWrapper, View> {
        val md3 = wrap(context)
        return md3 to LayoutInflater.from(md3).inflate(layoutId, null)
    }

    fun popupWidth(screenWidthPx: Int): Int =
        (screenWidthPx * WIDTH_FRACTION).toInt().coerceAtLeast(1)

    fun popupHeight(screenHeightPx: Int): Int =
        (screenHeightPx * HEIGHT_FRACTION).toInt().coerceAtLeast(1)

    fun show(context: Context, view: View): AlertDialog {
        val md3 = view.context
        val dialog = MaterialAlertDialogBuilder(
            md3,
            com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog
        ).setView(view).create()
        dialog.show()
        val metrics = context.resources.displayMetrics
        applyPopupSize(dialog, view, metrics.widthPixels, metrics.heightPixels)
        dialog.window?.setDimAmount(0.6f)
        return dialog
    }

    fun applyPopupSize(dialog: AlertDialog, view: View, screenWidthPx: Int, screenHeightPx: Int) {
        val width = popupWidth(screenWidthPx)
        val height = popupHeight(screenHeightPx)
        dialog.window?.setLayout(width, height)
        val match = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        view.layoutParams = match
        (view.parent as? View)?.let { parent ->
            parent.layoutParams = parent.layoutParams?.apply {
                this.width = ViewGroup.LayoutParams.MATCH_PARENT
                this.height = ViewGroup.LayoutParams.MATCH_PARENT
            } ?: match
        }
    }
}
