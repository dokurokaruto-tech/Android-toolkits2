"""Source integration checks; not Android runtime tests."""
import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/java/com/example/kennys_dokidoki_wallpaper"


def source(name):
    return (SRC / f"{name}.kt").read_text()


class ChatEditorWiringTest(unittest.TestCase):
    def test_no_global_editor_save(self):
        code = source("ConciergeEditorDialog") + source("ConciergeEditorTools")
        self.assertNotIn("HistoryStore", code)
        self.assertNotIn("applyCard", code)
        self.assertNotIn("applyTag", code)
        self.assertIn("ConciergeEditorPolicy.canApply(before, read())", code)
        self.assertIn("job?.cancel()", code)

    def test_editor_draft_fields(self):
        card = source("MainActivity").split("private fun showEditPromptCardDialog(")[1]
        self.assertIn("etMainPrompt.text.toString(), etNegativePrompt.text.toString()", card)
        self.assertIn("etMainPrompt.setText(draft.main)", card)
        tag = source("TagPromptEditorActivity")
        self.assertIn('文章：${etName.text}', tag)
        self.assertIn("etText.setText(draft.main)", tag)
        self.assertIn("currentVariants.map { it.name }", tag)

    def test_only_content_starts(self):
        manager = source("ChatGenerationManager")
        self.assertEqual(manager.count("ChatReplyNotifications.content("), 2)
        self.assertIn("content(context, aiNode.id, token)", manager)
        self.assertIn("content(context, aiNode.id, content)", manager)
        progress = manager.split("private fun notifyProgress(")[1]
        self.assertNotIn("ChatReplyNotifications", progress)
        self.assertIn('!delta.isNull("content")', manager)
        self.assertIn("stream.completed(fullReply)", manager)

    def test_deep_links(self):
        activity = source("ChatOverlayActivity")
        calls = [line for line in activity.splitlines() if "ChatGenerationManager.startGeneration(" in line]
        self.assertEqual(len(calls), 2)
        self.assertTrue(all("currentImageEntry?.uri?.toString()" in line for line in calls))
        notifications = source("ChatReplyNotifications")
        self.assertIn("putExtra(EXTRA_SESSION, target.session)", notifications)
        self.assertIn('.appendPath(target.session).appendPath(node).appendPath(event.name)', notifications)
        self.assertIn("replySessionPinned = intent.getStringExtra(ChatReplyNotifications.EXTRA_SESSION)", activity)
        self.assertIn("ChatGenerationManager.currentTree(notifiedSession)", activity)

    def test_visibility_lifecycle(self):
        activity = source("ChatOverlayActivity")
        self.assertIn("ChatReplyNotifications.viewing(this, replyNoticeOwner, value)", activity)
        self.assertIn("ChatReplyNotifications.viewing(this, replyNoticeOwner, currentChatId)", activity)
        self.assertIn("ChatReplyNotifications.leave(replyNoticeOwner)", activity)
        self.assertIn("POST_NOTIFICATIONS", activity)
        self.assertIn('"返信通知のON / OFF"', activity)


if __name__ == "__main__":
    unittest.main()
