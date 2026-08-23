package com.example.kennys_dokidoki_wallpaper

import android.app.Activity
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** 起動時にライブ壁紙へ誘導する。勝手に切り替える権限は通常ない。 */
object WallpaperSetupCoordinator {
    fun offerIfNeeded(activity: Activity) {
        if (activity.isFinishing) return
        val prefs = activity.getSharedPreferences(WallpaperSetupPolicy.PREFS_NAME, Context.MODE_PRIVATE)
        val alreadyPrompted = prefs.getBoolean(WallpaperSetupPolicy.KEY_PROMPTED, false)
        val active = isOurWallpaperActive(activity)
        if (active) {
            markPrompted(activity)
            return
        }
        if (!WallpaperSetupPolicy.shouldPrompt(alreadyActive = false, alreadyPrompted = alreadyPrompted)) {
            return
        }
        if (tryEnableSilently(activity) && isOurWallpaperActive(activity)) {
            markPrompted(activity)
            Toast.makeText(activity, "ライブ壁紙に設定した。", Toast.LENGTH_SHORT).show()
            return
        }
        markPrompted(activity)
        showPrompt(activity)
    }

    fun isOurWallpaperActive(context: Context): Boolean {
        val info = try {
            WallpaperManager.getInstance(context).wallpaperInfo
        } catch (_: Exception) {
            null
        }
        return WallpaperSetupPolicy.isOurWallpaper(
            info?.packageName,
            info?.serviceName,
            context.packageName,
            MyWallpaperService::class.java.name
        )
    }

    fun openSystemPicker(context: Context) {
        val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
            putExtra(
                WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                ComponentName(context, MyWallpaperService::class.java)
            )
        }
        context.startActivity(intent)
    }

    private fun tryEnableSilently(context: Context): Boolean {
        return try {
            val manager = WallpaperManager.getInstance(context)
            val method = manager.javaClass.getMethod("setWallpaperComponent", ComponentName::class.java)
            method.invoke(manager, ComponentName(context, MyWallpaperService::class.java))
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun markPrompted(context: Context) {
        context.getSharedPreferences(WallpaperSetupPolicy.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(WallpaperSetupPolicy.KEY_PROMPTED, true)
            .apply()
    }

    private fun showPrompt(activity: Activity) {
        val md3 = Md3PopupDialog.wrap(activity)
        val dialog = MaterialAlertDialogBuilder(
            md3,
            com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog
        )
            .setTitle(WallpaperSetupPolicy.TITLE)
            .setMessage(WallpaperSetupPolicy.MESSAGE)
            .setNegativeButton(WallpaperSetupPolicy.NEGATIVE, null)
            .setPositiveButton(WallpaperSetupPolicy.POSITIVE) { _, _ ->
                openSystemPicker(activity)
            }
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            ?.setTextColor(0xFFD0BCFF.toInt())
    }
}
