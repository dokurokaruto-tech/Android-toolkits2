"""Tool Finder selection and reveal animation source contracts.

Run: python3 -m unittest tools/test_tool_finder.py
"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'app/src/main/java/com/example/kennys_dokidoki_wallpaper/MyWallpaperService.kt'


def block(text, start, end):
    start_index = text.index(start)
    return text[start_index:text.index(end, start_index)]


class ToolFinderInteractionTest(unittest.TestCase):
    def setUp(self):
        self.source = SOURCE.read_text(encoding='utf-8')
        self.overlay = block(self.source, 'private class ToolFinderOverlay(', '\n}\n')

    def test_expanded_tools_need_outward_drag(self):
        update = block(self.overlay, '    fun update(', '    fun isAppSelected()')
        expanded = update.split('if (!expanded)', 1)[1]

        self.assertIn('distance <= baseRadius', expanded)
        self.assertIn('selectedTarget = ToolFinderTarget.APP', expanded)
        self.assertIn('distance < optionPickRadius', expanded)
        self.assertLess(expanded.index('distance < optionPickRadius'), expanded.index('val angle'))

    def test_app_remains_selected_inside_expanded_wheel(self):
        update = block(self.overlay, '    fun update(', '    fun isAppSelected()')
        expanded = update.split('if (!expanded)', 1)[1]
        app_select = expanded.index('selectedTarget = ToolFinderTarget.APP')
        option_angle = expanded.index('val angle')
        self.assertLess(app_select, option_angle)

    def test_reveal_opacity_uses_eased_progress(self):
        draw = block(self.overlay, '    fun draw(canvas: Canvas)', '    private fun drawWheel(')
        self.assertIn('easeInOut(revealProgress)', draw)
        self.assertIn('easeInOut(exitProgress)', draw)
        self.assertIn('private fun easeInOut(', self.overlay)


if __name__ == '__main__':
    unittest.main()
