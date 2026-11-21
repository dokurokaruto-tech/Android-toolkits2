"""Source and layout checks for model-picker reasoning toggle and live reasoning accordion."""
import pathlib
import unittest
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/java/com/example/kennys_dokidoki_wallpaper"
LAYOUT = ROOT / "app/src/main/res/layout"
DRAWABLE = ROOT / "app/src/main/res/drawable"
ANDROID_NS = "{http://schemas.android.com/apk/res/android}"


def source(name: str) -> str:
    return (SRC / f"{name}.kt").read_text(encoding="utf-8")


class ChatReasoningToggleTest(unittest.TestCase):
    def test_chat_overlay_has_no_reasoning_button_above_input(self):
        tree = ET.parse(LAYOUT / "activity_chat_overlay.xml")
        root = tree.getroot()
        by_id = {
            elem.attrib.get(f"{ANDROID_NS}id", "").split("/")[-1]: elem
            for elem in root.iter()
            if f"{ANDROID_NS}id" in elem.attrib
        }
        self.assertNotIn("reasoning_chip_bar", by_id)
        self.assertNotIn("btn_reasoning_toggle", by_id)

        bottom = by_id["bottom_controls_container"]
        child_ids = [
            child.attrib.get(f"{ANDROID_NS}id", "").split("/")[-1]
            for child in list(bottom)
        ]
        self.assertEqual(child_ids, ["extra_menu_scroll", "input_container"])

    def test_model_picker_dialogs_and_local_manager_expose_reasoning_switch(self):
        picker = ET.parse(LAYOUT / "dialog_model_picker_md3.xml").getroot()
        picker_ids = {
            elem.attrib.get(f"{ANDROID_NS}id", "").split("/")[-1]: elem
            for elem in picker.iter()
            if f"{ANDROID_NS}id" in elem.attrib
        }
        self.assertIn("layout_picker_reasoning", picker_ids)
        self.assertIn("switch_picker_reasoning", picker_ids)
        self.assertEqual(
            picker_ids["layout_picker_reasoning"].attrib.get(f"{ANDROID_NS}visibility"),
            "gone",
        )

        providers = ET.parse(LAYOUT / "dialog_openrouter_providers.xml").getroot()
        provider_ids = {
            elem.attrib.get(f"{ANDROID_NS}id", "").split("/")[-1]: elem
            for elem in providers.iter()
            if f"{ANDROID_NS}id" in elem.attrib
        }
        self.assertIn("layout_provider_reasoning", provider_ids)
        self.assertIn("switch_provider_reasoning", provider_ids)
        self.assertEqual(
            provider_ids["layout_provider_reasoning"].attrib.get(f"{ANDROID_NS}visibility"),
            "gone",
        )

        local_mgr = ET.parse(LAYOUT / "activity_local_model.xml").getroot()
        local_ids = {
            elem.attrib.get(f"{ANDROID_NS}id", "").split("/")[-1]: elem
            for elem in local_mgr.iter()
            if f"{ANDROID_NS}id" in elem.attrib
        }
        self.assertIn("layout_local_reasoning", local_ids)
        self.assertIn("switch_local_reasoning", local_ids)

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

    def test_kotlin_screens_wire_model_picker_switches_and_accordion(self):
        activity = source("ChatOverlayActivity")
        self.assertIn("findViewById<MaterialSwitch>(R.id.switch_picker_reasoning)", activity)
        self.assertIn("ChatReasoningPolicy.resolveAccordion(", activity)
        self.assertIn("ChatReasoningPolicy.headerLabel(", activity)
        self.assertIn("bindReasoningSection(holder, node)", activity)
        self.assertIn("reasoningCapability = ChatReasoningPolicy.capabilityOf(obj)", activity)

        provider_picker = source("OpenRouterProviderPicker")
        self.assertIn("R.id.switch_provider_reasoning", provider_picker)
        self.assertIn("ChatReasoningPolicy.Capability.SUPPORTED", provider_picker)

        local_activity = source("LocalModelActivity")
        self.assertIn("R.id.switch_local_reasoning", local_activity)

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
