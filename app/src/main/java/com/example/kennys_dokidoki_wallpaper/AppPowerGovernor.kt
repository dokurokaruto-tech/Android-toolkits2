package com.example.kennys_dokidoki_wallpaper

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * アプリを開いているかどうかを数え、背面の瞬間に常駐の電池消費を全部止める。
 * 壁紙サービスの表示と、ユーザーが明示的に始めた仕事（生成・LLM生成）は残す。
 *
 * Activity 0 → sleep（心拍停止・監視停止・RAM上の画像を全開放）
 *              │ 起動中 Activity 数（Policy で数える）
 * Activity 1+ → foreground（監視と心拍を再開）
 */
internal object AppPowerGovernor {
    private val lock = Any()
    private var startedActivities = 0
    private var app: Application? = null

    fun install(application: Application) {
        synchronized(lock) {
            if (app != null) {
                return
            }
            app = application
        }
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                onCountDelta(1)
            }

            override fun onActivityStopped(activity: Activity) {
                onCountDelta(-1)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    private fun onCountDelta(delta: Int) {
        val transition = synchronized(lock) {
            val before = AppPowerModePolicy.modeFor(startedActivities)
            startedActivities = AppPowerModePolicy.countAfter(startedActivities, delta)
            val after = AppPowerModePolicy.modeFor(startedActivities)
            if (before == after) null else after
        } ?: return
        val application = app ?: return
        when (transition) {
            AppPowerModePolicy.Mode.SLEEPING -> enterSleep(application)
            AppPowerModePolicy.Mode.FOREGROUND -> enterForeground(application)
        }
    }

    private fun enterSleep(application: Application) {
        ImageMemoryGovernor.onAppSleeping(application)
        GeneratedLibraryLiveUpdate.onAppSleeping()
    }

    private fun enterForeground(application: Application) {
        ImageMemoryGovernor.onAppWaking(application)
        GeneratedLibraryLiveUpdate.onAppWaking()
    }
}
