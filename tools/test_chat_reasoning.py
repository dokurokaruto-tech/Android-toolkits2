"""Source and layout checks for chat reasoning toggle."""
import pathlib
import unittest
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/java/com/example/kennys_dokidoki_wallpaper"
LAYOUT = ROOT / "app/src/main/res/layout"
ANDROID_NS = "{http://schemas.android.com/apk/res/android}"
APP_NS = "{http://schemas.android.com/apk/res-auto}"


def source(name: str) -> str:
    return (SRC / f"{name}.kt").read_text(encoding="utf-8")


class ChatReasoningToggleTest(unittest.TestCase):
    def test_chat_overlay_layout_has_reasoning_toggle_pill(self):
        tree = ET.parse(LAYOUT / "activity_chat_overlay.xml")
        root = tree.getroot()
        by_id = {
            elem.attrib.get(f"{ANDROID_NS}id", "").split("/")[-1]: elem
            for elem in root.iter()
            if f"{ANDROID_NS}id" in elem.attrib
        }
        self.assertIn("btn_reasoning_toggle", by_id)
        pill = by_id["btn_reasoning_toggle"]
        self.assertEqual(pill.attrib.get(f"{ANDROID_NS}visibility"), "gone")
        self.assertEqual(
            pill.attrib.get(f"{APP_NS}layout_constraintEnd_toStartOf"),
            "@id/btn_current_model",
        )

    def test_chat_overlay_wires_reasoning_button_and_menus(self):
        activity = source("ChatOverlayActivity")
        self.assertIn("findViewById(R.id.btn_reasoning_toggle)", activity)
        self.assertIn("refreshReasoningButton()", activity)
        self.assertIn("toggleReasoningMode()", activity)
        self.assertIn("ChatReasoningPolicy.isCurrentTargetSupported(", activity)
        self.assertIn('"推論（思考）のON / OFF"', activity)
        self.assertIn("reasoningCapability = ChatReasoningPolicy.capabilityOf(obj)", activity)

    def test_generation_and_retry_apply_reasoning_policy(self):
        manager = source("ChatGenerationManager")
        self.assertIn("ChatReasoningPolicy.applyOpenRouter(prefs, modelName, this)", manager)
        self.assertIn("ChatReasoningPolicy.applyLocalPrompt(", manager)
        self.assertIn("ChatReasoningPolicy.stripThinkBlocks(", manager)

        retry = source("ChatSuggestionRetry")
        self.assertIn("ChatReasoningPolicy.applyOpenRouter(prefs, model, this)", retry)
        self.assertIn("ChatReasoningPolicy.applyLocalPrompt(", retry)
        self.assertIn("ChatReasoningPolicy.stripThinkBlocks(", retry)

    def test_xml_files_parse_cleanly(self):
        for xml_path in LAYOUT.glob("*.xml"):
            ET.parse(xml_path)


if __name__ == "__main__":
    unittest.main()
