"""Source and layout checks for MD3 chat reasoning filter chip and live reasoning accordion."""
import pathlib
import unittest
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/java/com/example/kennys_dokidoki_wallpaper"
LAYOUT = ROOT / "app/src/main/res/layout"
DRAWABLE = ROOT / "app/src/main/res/drawable"
ANDROID_NS = "{http://schemas.android.com/apk/res/android}"
APP_NS = "{http://schemas.android.com/apk/res-auto}"


def source(name: str) -> str:
    return (SRC / f"{name}.kt").read_text(encoding="utf-8")


class ChatReasoningToggleTest(unittest.TestCase):
    def test_md3_reasoning_chip_sits_directly_above_input_container(self):
        tree = ET.parse(LAYOUT / "activity_chat_overlay.xml")
        root = tree.getroot()
        by_id = {
            elem.attrib.get(f"{ANDROID_NS}id", "").split("/")[-1]: elem
            for elem in root.iter()
            if f"{ANDROID_NS}id" in elem.attrib
        }
        self.assertIn("bottom_controls_container", by_id)
        self.assertIn("reasoning_chip_bar", by_id)
        self.assertIn("btn_reasoning_toggle", by_id)
        self.assertIn("iv_reasoning_chip_icon", by_id)
        self.assertIn("tv_reasoning_chip_label", by_id)

        bottom = by_id["bottom_controls_container"]
        child_ids = [
            child.attrib.get(f"{ANDROID_NS}id", "").split("/")[-1]
            for child in list(bottom)
        ]
        chip_idx = child_ids.index("reasoning_chip_bar")
        input_idx = child_ids.index("input_container")
        self.assertEqual(chip_idx + 1, input_idx)
        self.assertEqual(by_id["reasoning_chip_bar"].attrib.get(f"{ANDROID_NS}visibility"), "gone")

        # Top header bar no longer hosts btn_reasoning_toggle
        self.assertNotIn(
            f"{APP_NS}layout_constraintEnd_toStartOf",
            by_id["btn_reasoning_toggle"].attrib,
        )

    def test_item_chat_message_has_reasoning_accordion_above_ai_text(self):
        tree = ET.parse(LAYOUT / "item_chat_message.xml")
        root = tree.getroot()
        by_id = {
            elem.attrib.get(f"{ANDROID_NS}id", "").split("/")[-1]: elem
            for elem in root.iter()
            if f"{ANDROID_NS}id" in elem.attrib
        }
        for required_id in (
            "bubble_ai",
            "layout_reasoning_section",
            "btn_reasoning_header",
            "tv_reasoning_header",
            "iv_reasoning_chevron",
            "layout_reasoning_body",
            "tv_reasoning_body",
            "text_message_ai",
        ):
            self.assertIn(required_id, by_id)

        bubble = by_id["bubble_ai"]
        bubble_child_ids = [
            child.attrib.get(f"{ANDROID_NS}id", "").split("/")[-1]
            for child in list(bubble)
        ]
        self.assertLess(
            bubble_child_ids.index("layout_reasoning_section"),
            bubble_child_ids.index("text_message_ai"),
        )
        self.assertEqual(by_id["layout_reasoning_section"].attrib.get(f"{ANDROID_NS}visibility"), "gone")
        self.assertEqual(by_id["layout_reasoning_body"].attrib.get(f"{ANDROID_NS}visibility"), "gone")

    def test_chat_overlay_wires_md3_chip_and_reasoning_accordion(self):
        activity = source("ChatOverlayActivity")
        self.assertIn("findViewById(R.id.reasoning_chip_bar)", activity)
        self.assertIn("findViewById(R.id.btn_reasoning_toggle)", activity)
        self.assertIn("R.drawable.bg_md3_filter_chip_on", activity)
        self.assertIn("R.drawable.bg_md3_filter_chip_off", activity)
        self.assertIn("ChatReasoningPolicy.resolveAccordion(", activity)
        self.assertIn("ChatReasoningPolicy.headerLabel(", activity)
        self.assertIn("bindReasoningSection(holder, node)", activity)
        self.assertIn('"推論（思考）のON / OFF"', activity)
        self.assertIn("reasoningCapability = ChatReasoningPolicy.capabilityOf(obj)", activity)

    def test_generation_and_retry_apply_reasoning_policy(self):
        manager = source("ChatGenerationManager")
        self.assertIn("ChatReasoningPolicy.applyOpenRouter(prefs, modelName, this)", manager)
        self.assertIn("ChatReasoningPolicy.reasoningDeltaFromChunk(delta)", manager)
        self.assertIn("ChatReasoningPolicy.extract(", manager)
        self.assertIn("ChatReasoningPolicy.applyLocalPrompt(", manager)

        retry = source("ChatSuggestionRetry")
        self.assertIn("ChatReasoningPolicy.RequestPurpose.BACKGROUND_HELPER", retry)
        self.assertIn("ChatReasoningPolicy.applyLocalPrompt(", retry)
        self.assertIn("ChatReasoningPolicy.stripThinkBlocks(", retry)

    def test_xml_resources_parse_cleanly(self):
        for xml_path in LAYOUT.glob("*.xml"):
            ET.parse(xml_path)
        for xml_path in DRAWABLE.glob("*.xml"):
            ET.parse(xml_path)


if __name__ == "__main__":
    unittest.main()
