package com.example.kennys_dokidoki_wallpaper

import android.app.Activity
import android.app.WallpaperManager
import android.app.WallpaperInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** 起動時にライブ壁紙へ誘導する。勝手に切り替える権限は通常ない。 */
object WallpaperSetupCoordinator {
    @Volatile
    private var askedThisSession = false

    fun offerIfNeeded(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        val active = isOurWallpaperActive(activity)
        if (!WallpaperSetupPolicy.shouldPrompt(alreadyActive = active, askedThisSession = askedThisSession)) {
            return
        }
        if (tryEnableSilently(activity) && isOurWallpaperActive(activity)) {
            askedThisSession = true
            Toast.makeText(activity, "ライブ壁紙に設定した。", Toast.LENGTH_SHORT).show()
            return
        }
        askedThisSession = true
        showPrompt(activity)
    }

    fun isOurWallpaperActive(context: Context): Boolean {
        val info = homeWallpaperInfo(context)
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

    private fun homeWallpaperInfo(context: Context): WallpaperInfo? {
        val manager = try {
            WallpaperManager.getInstance(context)
        } catch (_: Exception) {
            return null
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                val method = manager.javaClass.getMethod("getWallpaperInfo", Int::class.javaPrimitiveType)
                val flagged = method.invoke(manager, WallpaperManager.FLAG_SYSTEM) as? WallpaperInfo
                if (flagged != null) return flagged
            } catch (_: Exception) {
            }
        }
        return try {
            manager.wallpaperInfo
        } catch (_: Exception) {
            null
        }
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
