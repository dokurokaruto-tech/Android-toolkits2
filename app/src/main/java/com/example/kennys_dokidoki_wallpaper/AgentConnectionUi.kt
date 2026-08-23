package com.example.kennys_dokidoki_wallpaper

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

object AgentConnectionUi {
    fun showDiagnosis(context: Context, diagnosis: AgentConnectionDiagnosis, title: String = "PC接続の失敗") {
        val text = diagnosis.displayText()
        val dialog = AlertDialog.Builder(context, R.style.Theme_Kennys_dokidoki_wallpaper)
            .setTitle(title)
            .setMessage(text)
            .setPositiveButton("閉じる", null)
            .setNeutralButton("コピー") { _, _ -> copy(context, text) }
            .setNegativeButton("ログ全部") { _, _ -> showLog(context) }
            .create()
        dialog.show()
        dialog.findViewById<TextView>(android.R.id.message)?.apply {
            setTextIsSelectable(true)
            textSize = 13f
        }
    }

    fun showLog(context: Context) {
        val text = AgentConnectionLog.formatAll(context)
        val dialog = AlertDialog.Builder(context, R.style.Theme_Kennys_dokidoki_wallpaper)
            .setTitle("PC接続ログ")
            .setMessage(text)
            .setPositiveButton("閉じる", null)
            .setNeutralButton("コピー") { _, _ -> copy(context, text) }
            .create()
        dialog.show()
        dialog.findViewById<TextView>(android.R.id.message)?.apply {
            setTextIsSelectable(true)
            textSize = 12f
        }
    }

    fun copy(context: Context, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("pc_connection_log", text))
        Toast.makeText(context, "接続ログをコピーした。", Toast.LENGTH_SHORT).show()
    }
}
