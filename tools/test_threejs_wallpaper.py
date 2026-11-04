"""Source contracts for the built-in three.js wallpaper foundation.

Not device performance tests: they pin the wiring between the asset player,
the data model, the wallpaper engine, the set editor and the management UI.
"""
from pathlib import Path
import json
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/java/com/example/kennys_dokidoki_wallpaper'
ASSETS = ROOT / 'app/src/main/assets/threejs'


def code(name):
    return (SRC / (name + '.kt')).read_text()


class AssetTest(unittest.TestCase):
    def test_library_is_pinned_compatible_revision(self):
        lib = (ASSETS / 'three.min.js').read_text()
        # r116 keeps both the legacy CubeCamera API (work 32) and InstancedMesh (work 36).
        self.assertIn('REVISION="116"', lib)
        for symbol in ('InstancedMesh', 'BoxBufferGeometry', 'LuminanceFormat', 'PMREMGenerator'):
            self.assertIn(symbol, lib)

    def test_player_and_works_present(self):
        self.assertTrue((ASSETS / 'player.html').is_file())
        self.assertTrue((ASSETS / 'player.js').is_file())
        for work in ('32', '36', '38'):
            data = json.loads((ASSETS / f'works/{work}.json').read_text())
            self.assertIn('scene', data)
            self.assertIn('scripts', data)
            self.assertTrue((ASSETS / f'works/posters/{work}.webp').is_file())

    def test_player_runs_editor_scripts_like_app_player(self):
        player = (ASSETS / 'player.js').read_text()
        self.assertIn('player,renderer,scene,camera', player)
        self.assertIn('new Function(', player)
        self.assertIn('events.update', player)
        # Legacy envMap render-target normalization for old works.
        self.assertIn('isWebGLRenderTarget', player)
        # No touch handling: gestures stay on the native engine.
        self.assertNotIn('addEventListener( \'pointerdown\'', player)


class DataModelTest(unittest.TestCase):
    def test_image_set_carries_flag_and_empty_pool(self):
        src = code('ImageSet')
        self.assertIn('var threeJsEnabled: Boolean = false', src)
        self.assertIn('if (threeJsEnabled) return emptyList()', src)
        self.assertIn('val ImageSet?.isThreeJs', src)

    def test_data_manager_persists_flag(self):
        src = code('DataManager')
        self.assertIn('threeJsEnabled = obj.optBoolean("threeJsEnabled", false)', src)
        self.assertIn('put("threeJsEnabled", set.threeJsEnabled)', src)

    def test_backup_round_trips_flag(self):
        src = code('BackupManager')
        self.assertIn('put("threeJsEnabled", set.threeJsEnabled)', src)
        self.assertIn('set.threeJsEnabled = obj.optBoolean("threeJsEnabled", false)', src)

    def test_catalog_ids_match_assets(self):
        src = code('ThreeJsWork')
        for work in ('32', '36', '38'):
            self.assertIn(f'("{work}"', src)
            self.assertTrue((ASSETS / f'works/{work}.json').is_file())


class EngineTest(unittest.TestCase):
    def test_reload_paths_branch_to_three_js(self):
        src = code('MyWallpaperService')
        # Every reload entry point consults the three.js branch first.
        self.assertGreaterEqual(src.count('if (handleThreeJsReload())'), 4)
        self.assertIn('private fun handleThreeJsReload()', src)

    def test_next_image_gesture_picks_random_other_work(self):
        src = code('MyWallpaperService')
        self.assertIn('ThreeJsSetPolicy.nextIndex(currentWorkIndex, ThreeJsWork.count', src)
        self.assertIn('Random.nextInt(ThreeJsWork.count - 1)', src)

    def test_set_switch_handles_both_directions(self):
        src = code('MyWallpaperService')
        self.assertIn('private fun switchToSet(', src)
        self.assertIn('if (set.isThreeJs)', src)
        self.assertIn('val threeSnapshot = if (threeJsMode) threeRenderer?.snapshot()', src)

    def test_hidden_releases_renderer(self):
        src = code('MyWallpaperService')
        body = src.split('private fun goQuiet()')[1].split('private fun reloadCurrent')[0]
        self.assertIn('releaseThreeRenderer()', body)

    def test_index_save_uses_work_index_in_three_js_mode(self):
        src = code('MyWallpaperService')
        self.assertIn('val indexToSave = if (threeJsMode) currentWorkIndex else currentImageIndex', src)


class RendererTest(unittest.TestCase):
    def test_offscreen_gpu_pipeline(self):
        src = code('ThreeJsStageRenderer')
        self.assertIn('createVirtualDisplay', src)
        self.assertIn('ImageReader.newInstance', src)
        self.assertIn('Bitmap.wrapHardwareBuffer', src)
        self.assertIn('Presentation', src)

    def test_gestures_not_forwarded_to_webview(self):
        src = code('ThreeJsStageRenderer')
        self.assertNotIn('.dispatchTouchEvent(', src)
        self.assertNotIn('override fun onTouchEvent', src)
        self.assertNotIn('setOnTouchListener', src)

    def test_release_frees_display_and_buffers(self):
        src = code('ThreeJsStageRenderer')
        body = src.split('fun release()')[1].split('private fun loadWorkNow')[0]
        self.assertIn('virtualDisplay?.release()', body)
        self.assertIn('imageReader?.close()', body)
        self.assertIn('view.destroy()', body)


class EditorTest(unittest.TestCase):
    def test_toggle_locks_tags_and_persists(self):
        src = code('ImageTagEditorActivity')
        self.assertIn('setupThreeJsToggle', src)
        self.assertIn('picker.setLocked(threeJsEnabled)', src)
        self.assertIn('if (!threeJsEnabled)', src)
        self.assertIn('imageSet!!.threeJsEnabled = threeJsEnabled', src)
        self.assertIn('ThreeJsSetPolicy.DEFAULT_SET_NAME', src)

    def test_picker_adapter_honors_lock(self):
        src = code('CategorizedTagPickerAdapter')
        self.assertIn('fun setLocked(', src)
        self.assertIn('if (locked) return@setOnClickListener', src)
        self.assertIn('if (locked) return false', src)


class ManagementUiTest(unittest.TestCase):
    def test_set_card_shows_poster_and_skips_empty_guard(self):
        src = code('ImageSetAdapter')
        self.assertIn('ThreeJsWork.at(lastIndex).posterUri', src)
        self.assertIn('if (!set.threeJsEnabled && filteredEntries.isEmpty())', src)

    def test_album_detail_lists_builtin_works(self):
        src = code('AlbumDetailActivity')
        self.assertIn('showThreeJsWorks', src)
        self.assertIn('applyThreeJsWork', src)
        self.assertIn('ThreeJsWork.entries.forEachIndexed', src)


if __name__ == '__main__':
    unittest.main()
