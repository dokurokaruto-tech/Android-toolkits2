"""Theme regression guard; run unittest discover -s tools -p test_insertion_dialog_theme.py."""
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'app/src/main/java/com/example/kennys_dokidoki_wallpaper/GenerationInsertionCoordinator.kt'
ANDROID = '{http://schemas.android.com/apk/res/android}'


class InsertionDialogThemeTest(unittest.TestCase):
    def test_fullscreen_has_non_material_theme(self):
        root = ET.parse(ROOT / 'app/src/main/AndroidManifest.xml').getroot()
        activity = next(node for node in root.iter('activity')
                        if node.get(ANDROID + 'name') == '.FullScreenImageActivity')
        self.assertEqual('@style/Theme.AppCompat.NoActionBar', activity.get(ANDROID + 'theme'))

    def test_every_dialog_wraps_host(self):
        source = SOURCE.read_text()
        constructors = list(re.finditer(r'MaterialAlertDialogBuilder\(', source))
        self.assertGreaterEqual(len(constructors), 2)
        for call in constructors:
            argument = source[call.end():].lstrip()
            self.assertTrue(argument.startswith('Md3PopupDialog.wrap(activity)'),
                            'Insertion dialogs must not use the AppCompat-only activity theme')

    def test_offer_preserves_running_job(self):
        offer = SOURCE.read_text().split('fun offer(', 1)[1].split('private fun send(', 1)[0]
        self.assertNotIn('startGeneration(', offer)
        self.assertNotIn('endGeneration(', offer)
        self.assertNotIn('startActivity(', offer)
        self.assertNotIn('finish()', offer)


if __name__ == '__main__':
    unittest.main()
