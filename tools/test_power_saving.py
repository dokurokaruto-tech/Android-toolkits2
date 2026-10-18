"""閉じた後の省電力思想（心拍停止・監視停止・RAM解放・壁紙エンジン静止）の配線検査。

Run: python3 -m unittest tools/test_power_saving.py
"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'app/src/main/java/com/example/kennys_dokidoki_wallpaper'


def read(path):
    return (SOURCE / path).read_text(encoding='utf-8')


def block(text, start_marker, end_marker):
    start = text.index(start_marker)
    return text[start:text.index(end_marker, start)]


class AppPowerGovernorWiringTest(unittest.TestCase):
    def test_application_installs_governor(self):
        app = read('KennysApplication.kt')
        self.assertIn('AppPowerGovernor.install(this)', app)

    def test_governor_counts_activities_and_gates(self):
        governor = read('AppPowerGovernor.kt')
        self.assertIn('registerActivityLifecycleCallbacks', governor)
        self.assertIn('onActivityStarted', governor)
        self.assertIn('onActivityStopped', governor)
        self.assertIn('AppPowerModePolicy.modeFor(startedActivities)', governor)
        self.assertIn('AppPowerModePolicy.countAfter(startedActivities, delta)', governor)
        self.assertIn('ImageMemoryGovernor.onAppSleeping(application)', governor)
        self.assertIn('ImageMemoryGovernor.onAppWaking(application)', governor)
        self.assertIn('GeneratedLibraryLiveUpdate.onAppSleeping()', governor)
        self.assertIn('GeneratedLibraryLiveUpdate.onAppWaking()', governor)

    def test_policy_defines_sleep_mode(self):
        policy = read('AppPowerModePolicy.kt')
        self.assertIn('enum class Mode', policy)
        self.assertIn('SLEEPING', policy)
        self.assertIn('coerceAtLeast(0)', policy)


class MemoryGovernorSleepTest(unittest.TestCase):
    def setUp(self):
        self.governor = read('ImageMemoryGovernor.kt')

    def test_sleep_stops_heartbeat_and_clears_ram(self):
        sleep = block(self.governor, 'fun onAppSleeping(', 'fun noteWorkingSet(')
        self.assertIn('handler.removeCallbacks(heartbeat)', sleep)
        self.assertIn('OriginalImageMemoryCache.cancelTransfers()', sleep)
        self.assertIn('OriginalImageMemoryCache.clear()', sleep)
        self.assertIn('Glide.get(', sleep)
        self.assertIn('clearMemory()', sleep)

    def test_wake_restarts_heartbeat_later(self):
        wake = block(self.governor, 'fun onAppWaking(', 'fun onAppSleeping(')
        self.assertIn('handler.removeCallbacks(heartbeat)', wake)
        self.assertIn('handler.postDelayed(heartbeat, ImageMemoryPressurePolicy.HEARTBEAT_MS)', wake)

    def test_heartbeat_guards_itself(self):
        heartbeat = block(self.governor, 'private val heartbeat', 'fun start(')
        self.assertIn('heartbeatActive', heartbeat)


class GeneratedLibrarySleepTest(unittest.TestCase):
    def setUp(self):
        self.live = read('GeneratedLibraryLiveUpdate.kt')

    def test_sleep_cancels_poll(self):
        sleep = block(self.live, 'fun onAppSleeping(', 'private fun startPolling(')
        self.assertIn('pollJob?.cancel()', sleep)
        self.assertIn('pollJob = null', sleep)

    def test_wake_restarts_for_subscribers(self):
        wake = block(self.live, 'fun onAppWaking(', 'fun onAppSleeping(')
        self.assertIn('subscribers > 0', wake)
        self.assertIn('startPolling()', wake)

    def test_bind_does_not_start_while_sleeping(self):
        bind = block(self.live, 'fun bind(', 'fun unbind(')
        self.assertIn('if (!sleeping)', bind)
        self.assertIn('startPolling()', bind)


class WallpaperEngineSleepTest(unittest.TestCase):
    def setUp(self):
        self.engine = read('MyWallpaperService.kt')

    def test_visibility_quietens_engine(self):
        vis = block(self.engine, 'override fun onVisibilityChanged(', 'private fun goQuiet(')
        self.assertIn('engineVisible = visible', vis)
        self.assertIn('goQuiet()', vis)
        self.assertIn('reloadCurrent()', vis)

    def test_quiet_drops_bitmaps_and_callbacks(self):
        quiet = block(self.engine, 'private fun goQuiet(', 'private fun reloadCurrent(')
        self.assertIn('animator?.cancel()', quiet)
        self.assertIn('textAnimator?.cancel()', quiet)
        self.assertIn('tapHandler.removeCallbacksAndMessages(null)', quiet)
        self.assertIn('textHandler.removeCallbacksAndMessages(null)', quiet)
        self.assertIn('currentBitmap = null', quiet)
        self.assertIn('previousBitmap = null', quiet)

    def test_hidden_changes_are_deferred(self):
        self.assertIn('needsReload = true', self.engine)
        prefs = block(self.engine, 'override fun onSharedPreferenceChanged(', 'override fun onTouchEvent(')
        self.assertIn('if (!engineVisible)', prefs)
        self.assertIn('return@post', prefs)

    def test_draw_and_text_skip_when_hidden(self):
        draw = block(self.engine, 'private fun draw()', 'private fun calculateSrcRect(')
        self.assertIn('if (!engineVisible) return', draw)
        text = block(self.engine, 'private fun showSetNameText(', 'private fun startTextFadeOut(')
        self.assertIn('if (!engineVisible) return', text)

    def test_async_loads_discard_when_hidden(self):
        load = block(self.engine, 'private fun loadAndSetCurrentBitmap(', 'private fun startCrossfadeToNextImage(')
        self.assertIn('if (!engineVisible)', load)
        self.assertIn('return@withContext', load)

    def test_destroy_also_quietens(self):
        destroy = block(self.engine, 'override fun onDestroy()', 'override fun onSharedPreferenceChanged(')
        self.assertIn('goQuiet()', destroy)

    def test_hidden_animation_cancel_does_not_broadcast(self):
        save = block(self.engine, 'private fun saveCurrentIndex()', 'private fun startAnimator()')
        self.assertIn('if (!engineVisible) return', save)


class OriginalCacheSleepTest(unittest.TestCase):
    def test_cancel_transfers_exists_and_cancels_jobs(self):
        cache = read('OriginalImageMemoryCache.kt')
        cancel = block(cache, 'fun cancelTransfers()', 'fun entryCount()')
        self.assertIn('downloadJobs.values.toList()', cancel)
        self.assertIn('it.cancel()', cancel)


if __name__ == '__main__':
    unittest.main()
