from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from generation_agent.config import AgentConfig, load_config_values
from tools.tts_setup import DEFAULT_MODEL_DIR, DEFAULT_TTS_PYTHON, model_issues, prepare_config


class LocalConfigTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.config = self.root / "config.json"

    def test_local_values_override_only_present_keys(self):
        self.config.write_text(json.dumps({"listen_port": 3001, "api_key": "old-key", "sd_base_url": "http://forge:7860"}))
        (self.root / "config.local.json").write_text(json.dumps({"api_key": "local-key", "tts_model_dir": "existing-model"}))
        loaded = AgentConfig.load(self.config)
        self.assertEqual(loaded.listen_port, 3001)
        self.assertEqual(loaded.api_key, "local-key")
        self.assertEqual(loaded.sd_base_url, "http://forge:7860")
        self.assertEqual(loaded.tts_model_dir, self.root / "existing-model")

    def test_bom_and_custom_config_filename(self):
        self.config = self.root / "custom.json"
        self.config.write_text('{"listen_port":3002}', encoding="utf-8-sig")
        self.config.with_suffix(".local.json").write_text('{"tts_python":"env/python"}', encoding="utf-8-sig")
        loaded = AgentConfig.load(self.config)
        self.assertEqual(loaded.listen_port, 3002)
        self.assertEqual(loaded.tts_python, str(self.root / "env/python"))

    def test_invalid_local_json_is_not_ignored(self):
        (self.root / "config.local.json").write_text("[]")
        with self.assertRaisesRegex(ValueError, "JSON object"):
            AgentConfig.load(self.config)

    def test_old_config_without_local_still_loads(self):
        self.config.write_text('{"api_key":"existing-key","listen_port":3010}')
        config = AgentConfig.load(self.config)
        self.assertEqual(config.listen_port, 3010)
        self.assertEqual(config.api_key, "existing-key")

    @patch("tools.tts_setup.secrets.token_urlsafe", return_value="test-generated-key")
    def test_prepare_is_private_and_idempotent(self, generate):
        self.config.write_text('{"api_key":"","tts_model_dir":"","tts_python":""}')
        original = self.config.read_bytes()
        local = prepare_config(self.config)
        first = local.read_bytes()
        self.assertEqual(self.config.read_bytes(), original)
        values = load_config_values(self.config)
        self.assertEqual(values["api_key"], "test-generated-key")
        self.assertEqual(values["tts_model_dir"], DEFAULT_MODEL_DIR)
        self.assertEqual(values["tts_python"], DEFAULT_TTS_PYTHON)
        prepare_config(self.config)
        self.assertEqual(local.read_bytes(), first)
        generate.assert_called_once()
        self.assertFalse(local.with_suffix(".tmp").exists())

    @patch("tools.tts_setup.secrets.token_urlsafe")
    def test_prepare_preserves_pc_values(self, generate):
        self.config.write_text(json.dumps({"api_key": "existing-key", "listen_port": 3100,
                                          "sd_base_url": "http://pc:7870", "civitai_api_key": "existing-civitai"}))
        local = self.config.with_suffix(".local.json")
        local.write_text(json.dumps({"tts_python": "custom/python.exe", "tts_model_dir": "my-model"}))
        prepare_config(self.config)
        values = json.loads(local.read_text())
        self.assertEqual(values["api_key"], "existing-key")
        self.assertEqual(values["listen_port"], 3100)
        self.assertEqual(values["sd_base_url"], "http://pc:7870")
        self.assertEqual(values["civitai_api_key"], "existing-civitai")
        self.assertEqual(values["tts_model_dir"], "my-model")
        self.assertEqual(values["tts_python"], "custom/python.exe")
        generate.assert_not_called()

    def test_explicit_model_path_only_changes_model(self):
        self.config.write_text('{"api_key":"existing-key","tts_model_dir":"old","listen_port":3005}')
        local = prepare_config(self.config, "C:/AI/My Models/Qwen3-TTS-12Hz-1.7B-Base")
        values = json.loads(local.read_text())
        self.assertEqual(values["tts_model_dir"], "C:/AI/My Models/Qwen3-TTS-12Hz-1.7B-Base")
        self.assertEqual(values["api_key"], "existing-key")
        self.assertEqual(values["listen_port"], 3005)

    def test_blank_explicit_path_does_not_write(self):
        with self.assertRaises(ValueError):
            prepare_config(self.config, "  ")
        self.assertFalse(self.config.with_suffix(".local.json").exists())

    def test_shipped_defaults_have_no_credentials(self):
        values = json.loads((ROOT / "config.json").read_text())
        self.assertEqual(values["api_key"], "")
        self.assertEqual(values["civitai_api_key"], "")
        self.assertEqual(values["tts_model_dir"], DEFAULT_MODEL_DIR)
        self.assertEqual(values["tts_python"], DEFAULT_TTS_PYTHON)
        self.assertEqual(values, json.loads((ROOT / "config.example.json").read_text()))


class ModelLayoutTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.model = Path(temporary.name)
        (self.model / "speech_tokenizer").mkdir()
        for name in ("config.json", "generation_config.json", "tokenizer_config.json", "tokenizer.json",
                     "speech_tokenizer/config.json", "speech_tokenizer/preprocessor_config.json"):
            (self.model / name).write_text('{"tts_model_type":"base"}')
        for name in ("model.safetensors", "speech_tokenizer/model.safetensors"):
            (self.model / name).write_bytes(b"test weight placeholder")

    def test_complete_layout(self):
        self.assertEqual(model_issues(self.model), [])

    def test_missing_model(self):
        self.assertTrue(model_issues(None))
        self.assertTrue(model_issues(self.model / "missing"))

    def test_missing_speech_tokenizer_is_reported(self):
        (self.model / "speech_tokenizer/model.safetensors").unlink()
        self.assertTrue(any("Missing model weights" in message for message in model_issues(self.model)))

    def test_wrong_model_variant(self):
        (self.model / "config.json").write_text('{"tts_model_type":"custom_voice"}')
        self.assertTrue(any("not CustomVoice" in message for message in model_issues(self.model)))

    def test_missing_shard(self):
        (self.model / "model.safetensors.index.json").write_text(json.dumps({"weight_map": {"x": "absent.safetensors"}}))
        self.assertTrue(any("absent.safetensors" in message for message in model_issues(self.model)))

    def test_vocab_and_merges_supported(self):
        (self.model / "tokenizer.json").unlink()
        (self.model / "vocab.json").write_text("{}")
        (self.model / "merges.txt").write_text("test")
        self.assertEqual(model_issues(self.model), [])


if __name__ == "__main__":
    unittest.main()
