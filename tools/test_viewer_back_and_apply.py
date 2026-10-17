"""Viewer back stability, card-set apply, and replay seed/batch wiring guards.

Run: python3 -m unittest tools/test_viewer_back_and_apply.py
"""
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'app/src/main/java/com/example/kennys_dokidoki_wallpaper'
LAYOUT = ROOT / 'app/src/main/res/layout'
ANDROID = '{http://schemas.android.com/apk/res/android}'


def read(path):
    return (SOURCE / path).read_text(encoding='utf-8')


def block(text, start_marker, end_marker):
    start = text.index(start_marker)
    return text[start:text.index(end_marker, start)]


class ViewerBackStabilityTest(unittest.TestCase):
    """戻る操作のクラッシュ源を塞いでいることの検査。"""

    def setUp(self):
        self.viewer = read('FullScreenImageActivity.kt')

    def test_request_manager_captured_while_alive(self):
        # Glide.with(activity)はdestroy済みActivityで例外を投げる。
        # onCreateでRequestManagerを確保し、以後それを使う。
        self.assertIn('private lateinit var glide: RequestManager', self.viewer)
        self.assertIn('import com.bumptech.glide.RequestManager', self.viewer)
        onCreate = block(self.viewer, 'override fun onCreate(', 'override fun onResume()')
        self.assertIn('glide = Glide.with(this)', onCreate)

    def test_destroy_never_calls_glide_with(self):
        onDestroy = block(self.viewer, 'override fun onDestroy()', 'private fun loadImages()')
        self.assertNotIn('Glide.with(', onDestroy)
        self.assertIn('glide.clear(', onDestroy)
        # 閉じた後のchrome自動非表示タイマーも止める。
        self.assertIn('chromeHandler.removeCallbacksAndMessages(null)', onDestroy)

    def test_close_clears_both_targets(self):
        close = block(self.viewer, 'private fun closeViewerImmediately()', 'override fun onDestroy()')
        self.assertIn('thumbnailTarget?.let { glide.clear(it) }', close)
        self.assertIn('activeTarget?.let { glide.clear(it) }', close)
        self.assertIn('chromeHandler.removeCallbacksAndMessages(null)', close)

    def test_no_glide_with_outside_oncreate(self):
        # 生存中の呼び出しも確保済みマネージャへ統一する。
        occurrences = [m.start() for m in re.finditer(r'Glide\.with\(', self.viewer)]
        self.assertEqual(len(occurrences), 1, 'Only onCreate may call Glide.with directly')

    def test_album_detail_unregisters_both_prefs(self):
        album = read('AlbumDetailActivity.kt')
        onDestroy = block(album, 'override fun onDestroy()', 'override fun onSharedPreferenceChanged(')
        self.assertNotIn('registerOnSharedPreferenceChangeListener', onDestroy.replace(
            'unregisterOnSharedPreferenceChangeListener', ''))
        self.assertEqual(onDestroy.count('unregisterOnSharedPreferenceChangeListener'), 2)


class ApplyCardSetWiringTest(unittest.TestCase):
    """閲覧中のイラストのカード構成を現在のセットへ上書きする経路の検査。"""

    def setUp(self):
        self.viewer = read('FullScreenImageActivity.kt')

    def test_layout_has_apply_button_in_chrome_bar(self):
        tree = ET.parse(LAYOUT / 'activity_full_screen_image.xml')
        bar = next(node for node in tree.iter() if node.get(ANDROID + 'id') == '@+id/viewer_chrome_bar')
        button = next(node for node in bar.iter() if node.get(ANDROID + 'id') == '@+id/btn_apply_card_set')
        self.assertEqual('true', button.get(ANDROID + 'clickable'))

    def test_viewer_offers_handoff(self):
        self.assertIn('btnApplyCardSet = findViewById(R.id.btn_apply_card_set)', self.viewer)
        self.assertIn('BuilderCardSetHandoff.offer(', self.viewer)
        self.assertIn('GeneratedImagePresetFactory.resolveSource(', self.viewer)
        # chromeと共に表示/非表示。生成ビューワー以外では出さない。
        actions = block(self.viewer, 'private fun setupViewerActions()', 'private fun showReplayDialog()')
        self.assertIn('btnApplyCardSet.visibility = View.GONE', actions)
        self.assertIn('btnApplyCardSet.visibility = View.VISIBLE', actions)
        self.assertIn('btnApplyCardSet', block(self.viewer, 'private fun chromeViews()', 'override fun onBackPressed()'))

    def test_main_activity_consumes_on_resume(self):
        main = read('MainActivity.kt')
        onResume = block(main, 'override fun onResume()', 'private fun applyPendingBuilderCardSet()')
        self.assertIn('applyPendingBuilderCardSet()', onResume)
        apply = block(main, 'private fun applyPendingBuilderCardSet()', 'private fun resumePendingCivitaiImport()')
        self.assertIn('BuilderCardSetHandoff.consume()', apply)
        self.assertIn('BuilderCardSetApplyPolicy.selectionFor(', apply)
        self.assertIn('PromptCardManager.randomEnabledCategories.clear()', apply)
        self.assertIn('PromptCardManager.saveInteractiveState(', apply)
        # undo履歴へ載せ、ビルダー画面を見せる。
        self.assertIn('commitBuilderState()', apply)
        self.assertIn('showSection(R.id.nav_builder)', apply)

    def test_preset_factory_exposes_resolve_source(self):
        factory = read('GeneratedImagePresetFactory.kt')
        self.assertRegex(
            factory,
            r'fun resolveSource\(\s*context: Context,\s*uri: Uri[^)]*\): GeneratedImagePresetPolicy\.Source\?'
        )
        save = block(factory, 'fun saveFromImage(', '/** 画像に使われたカード構成を解決する。')
        self.assertIn('resolveSource(context, uri', save)


class ReplaySeedBatchWiringTest(unittest.TestCase):
    """再生成ダイアログのシード変更・バッチ数・割り込み枚数の検査。"""

    def setUp(self):
        self.viewer = read('FullScreenImageActivity.kt')

    def test_dialog_layout_has_seed_and_batch(self):
        tree = ET.parse(LAYOUT / 'dialog_replay_generation.xml')
        ids = {node.get(ANDROID + 'id') for node in tree.iter()}
        self.assertIn('@+id/switch_replay_new_seed', ids)
        self.assertIn('@+id/et_replay_batch', ids)
        switch = next(node for node in tree.iter()
                      if node.get(ANDROID + 'id') == '@+id/switch_replay_new_seed')
        self.assertTrue(switch.tag.endswith('MaterialSwitch'))
        batch = next(node for node in tree.iter()
                     if node.get(ANDROID + 'id') == '@+id/et_replay_batch')
        self.assertEqual('number', batch.get(ANDROID + 'inputType'))
        self.assertEqual('false', batch.get(ANDROID + 'enabled'))

    def test_dialog_wires_seed_mode_and_batch(self):
        dialog = block(self.viewer, 'private fun showReplayDialog()', 'private fun defaultReplayBatch()')
        self.assertIn('R.id.switch_replay_new_seed', dialog)
        self.assertIn('R.id.et_replay_batch', dialog)
        # シード不明の画像はNEW固定。
        self.assertIn('seedSwitch.isChecked = !seedKnown', dialog)
        self.assertIn('seedSwitch.isEnabled = seedKnown', dialog)
        # バッチ入力はシードを変えるときだけ有効。
        self.assertIn('batchField.isEnabled = seedSwitch.isChecked', dialog)
        self.assertIn('GeneratedImageReplayPolicy.SeedMode.NEW', dialog)
        self.assertIn('GeneratedImageReplayPolicy.SeedMode.SAME', dialog)
        self.assertIn('GeneratedImageReplayPolicy.clampBatch(', dialog)

    def test_start_passes_all_requests(self):
        start = block(self.viewer, 'private fun startReplayGeneration(', 'private fun beginDeleteHold()')
        self.assertIn('GeneratedImageReplayPolicy.requests(recipe, steps, seedMode, batch)', start)
        self.assertIn('ImageGenerationCoordinator.start(this,', start)

    def test_default_batch_comes_from_builder_settings(self):
        default = block(self.viewer, 'private fun defaultReplayBatch()', 'private fun startReplayGeneration(')
        self.assertIn('gen_batch_count', default)

    def test_coordinator_inserts_every_request(self):
        # 割り込み時は渡された全リクエスト(=バッチ枚数)をそのまま差し込む。
        coordinator = read('ImageGenerationCoordinator.kt')
        busy = block(coordinator, 'if (isBusy() || GenerationAgentClient.hasPendingJob(activity)', 'val app = activity.applicationContext')
        self.assertIn('GenerationInsertionCoordinator.offer(activity, valid)', busy)


if __name__ == '__main__':
    unittest.main()
