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

    def test_clockwise_reveal_uses_eased_sweep_not_global_fade(self):
        draw = block(self.overlay, '    fun draw(canvas: Canvas)', '    private fun drawWheel(')
        self.assertIn('val sweepProgress = easeInOut(', draw)
        self.assertIn('revealPath(sweepProgress)', draw)
        self.assertNotIn('saveLayerAlpha', draw)
        self.assertNotIn('canvas.scale(', draw)
        self.assertIn('private fun easeInOut(', self.overlay)

    def test_clockwise_scanner_owns_opacity_animation(self):
        scanner = block(self.overlay, '    private fun drawScanner(', '    private fun revealPath(')
        self.assertIn('scannerPaint.alpha =', scanner)
        self.assertIn('Color.alpha(scannerColor) * sweepFraction', scanner)
        self.assertIn('closingSweepFraction * (1f - progress)', scanner)
        self.assertIn('ToolFinderSpec.FULL_CIRCLE_DEGREES * sweepFraction', scanner)

    def test_close_wipe_removes_content_clockwise(self):
        reveal = block(self.overlay, '    private fun revealPath(', '    private fun drawIcon(')
        self.assertIn('if (isClosing)', reveal)
        self.assertIn('closingSweepFraction * (1f - progress)', reveal)
        self.assertIn('ToolFinderSpec.FULL_CIRCLE_DEGREES * sweepFraction', reveal)
        close = block(self.overlay, '    fun beginClosing()', '    fun draw(canvas: Canvas)')
        self.assertIn('closingSweepFraction = easeInOut(revealProgress)', close)

    def test_vertical_center_stays_at_touch_point(self):
        anchor = block(self.overlay, '    val centerX =', '    var revealProgress =')
        self.assertIn('val centerY = touchY', anchor)
        self.assertNotIn('clampCenter(touchY', anchor)

    def test_release_commits_last_highlight(self):
        touch = block(
            self.source,
            'override fun onTouchEvent(event: MotionEvent?)',
            'private fun executeHoldAction('
        )
        release = block(
            touch,
            'MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {',
            '                    else -> {}'
        )
        self.assertNotIn('updateToolFinder', release)

    def test_sleep_clears_pending_gesture_state(self):
        quiet = block(self.source, 'private fun goQuiet()', 'private fun reloadCurrent()')
        self.assertIn('tapCount = 0', quiet)
        self.assertIn('lastTapTime = 0L', quiet)
        self.assertIn('holdRunnable = null', quiet)
        self.assertIn('longPressRunnable = null', quiet)


if __name__ == '__main__':
    unittest.main()
