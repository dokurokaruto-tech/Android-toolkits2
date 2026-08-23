package com.example.kennys_dokidoki_wallpaper

import android.app.Dialog
import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.appcompat.app.AppCompatDialog

/**
 * プロンプトカード／プリセット編集を、画面いっぱいにせず
 * Material Design 3 の浮きポップアップとして出す。
 * AlertDialog の wrap 制限を避け、中身の量に関わらず同じ縦幅にする。
 */
object Md3PopupDialog {
    const val WIDTH_FRACTION = 0.92f
    const val HEIGHT_FRACTION = 0.94f

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

    fun show(context: Context, view: View): AppCompatDialog {
        val dialog = AppCompatDialog(view.context, R.style.ThemeOverlay_Kennys_Md3Popup)
        dialog.supportRequestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(
            view,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
        val metrics = context.resources.displayMetrics
        applyPopupSize(dialog, view, metrics.widthPixels, metrics.heightPixels)
        dialog.window?.setDimAmount(0.6f)
        view.post { applyPopupSize(dialog, view, metrics.widthPixels, metrics.heightPixels) }
        return dialog
    }

    fun applyPopupSize(dialog: Dialog, view: View, screenWidthPx: Int, screenHeightPx: Int) {
        val width = popupWidth(screenWidthPx)
        val height = popupHeight(screenHeightPx)
        dialog.window?.setLayout(width, height)
        matchParentChain(view, width, height)
    }

    fun matchParentChain(view: View, minWidthPx: Int, minHeightPx: Int) {
        var current: View? = view
        while (current != null) {
            current.minimumWidth = minWidthPx
            current.minimumHeight = minHeightPx
            val params = current.layoutParams
            if (params != null) {
                params.width = ViewGroup.LayoutParams.MATCH_PARENT
                params.height = ViewGroup.LayoutParams.MATCH_PARENT
                current.layoutParams = params
            } else {
                current.layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
            current = current.parent as? View
        }
    }
}
