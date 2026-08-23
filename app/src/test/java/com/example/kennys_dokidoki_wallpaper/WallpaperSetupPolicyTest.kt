package com.example.kennys_dokidoki_wallpaper

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WallpaperSetupPolicyTest {
    @Test
    fun promptWhenHomeWallpaperIsNotThisApp() {
        assertTrue(WallpaperSetupPolicy.shouldPrompt(alreadyActive = false, askedThisSession = false))
        assertFalse(WallpaperSetupPolicy.shouldPrompt(alreadyActive = true, askedThisSession = false))
        assertFalse(WallpaperSetupPolicy.shouldPrompt(alreadyActive = false, askedThisSession = true))
        assertTrue(WallpaperSetupPolicy.shouldPrompt(alreadyActive = false))
    }

    @Test
    fun recognizesOnlyThisAppsWallpaperService() {
        assertTrue(
            WallpaperSetupPolicy.isOurWallpaper(
                "com.example.kennys_dokidoki_wallpaper",
                "com.example.kennys_dokidoki_wallpaper.MyWallpaperService",
                "com.example.kennys_dokidoki_wallpaper",
                "com.example.kennys_dokidoki_wallpaper.MyWallpaperService"
            )
        )
        assertFalse(
            WallpaperSetupPolicy.isOurWallpaper(
                "com.other.app",
                "com.other.app.Wp",
                "com.example.kennys_dokidoki_wallpaper",
                "com.example.kennys_dokidoki_wallpaper.MyWallpaperService"
            )
        )
        assertFalse(
            WallpaperSetupPolicy.isOurWallpaper(
                null,
                null,
                "com.example.kennys_dokidoki_wallpaper",
                "com.example.kennys_dokidoki_wallpaper.MyWallpaperService"
            )
        )
        assertTrue(
            WallpaperSetupPolicy.isOurWallpaper(
                "com.example.kennys_dokidoki_wallpaper",
                ".MyWallpaperService",
                "com.example.kennys_dokidoki_wallpaper",
                "com.example.kennys_dokidoki_wallpaper.MyWallpaperService"
            )
        )
    }
}
