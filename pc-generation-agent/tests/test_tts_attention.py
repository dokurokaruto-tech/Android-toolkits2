from __future__ import annotations

import io
import importlib
import sys
import types
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from generation_agent.qwen_runtime import import_qwen, _rewrite_notice
from generation_agent.tts_attention import prepare_attention, _use_repeat_kv
from generation_agent.tts_options import TtsAttention
from generation_agent.config import AgentConfig

BANNER = "\n********\nWarning: flash-attn is not installed. Will only run the manual PyTorch version. Please install flash-attn for faster inference.\n********\n "


class NoticeTest(unittest.TestCase):
    def test_banner_only(self):
        output = _rewrite_notice("before\n" + BANNER + "\nafter\n")
        self.assertIn("before\n", output)
        self.assertIn("after\n", output)
        self.assertIn("25Hz", output)
        self.assertNotIn("Will only run the manual", output)
        self.assertEqual(_rewrite_notice("Other warning: flash-attn failed"), "Other warning: flash-attn failed")

    def test_import_error_visible(self):
        def fail(name):
            print(BANNER)
            print("keep this diagnostic")
            raise ImportError("real import failure")
        output = io.StringIO()
        with redirect_stdout(output), patch("generation_agent.qwen_runtime.importlib.import_module", side_effect=fail):
            with self.assertRaisesRegex(ImportError, "real import failure"):
                import_qwen()
        self.assertIn("keep this diagnostic", output.getvalue())
        self.assertIsNot(sys.stdout, output)

    def test_import_returns_class(self):
        model = object()
        with patch("generation_agent.qwen_runtime.importlib.import_module",
                   return_value=types.SimpleNamespace(Qwen3TTSModel=model)):
            self.assertIs(import_qwen(), model)


class AttentionTest(unittest.TestCase):
    def test_config_validation(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "config.json"
            self.assertEqual(AgentConfig.load(path).tts_attention, TtsAttention.AUTO)
            path.write_text('{"tts_attention":"sdpa"}')
            self.assertEqual(AgentConfig.load(path).tts_attention, TtsAttention.SDPA)
            path.write_text('{"tts_attention":"flash_attention_2"}')
            with self.assertRaises(ValueError):
                AgentConfig.load(path)

    def test_scoped_eligibility(self):
        self.assertTrue(_use_repeat_kv((7, 5), "2.7.1+cu126", "4.57.3", TtsAttention.AUTO))
        for capability, torch, transformers, mode in (
                ((8, 6), "2.7.1+cu126", "4.57.3", TtsAttention.AUTO),
                ((7, 5), "2.8.0", "4.57.3", TtsAttention.AUTO),
                ((7, 5), "2.7.1+cu126", "5.0.0", TtsAttention.AUTO),
                ((7, 5), "2.7.1+cu126", "4.57.3", TtsAttention.SDPA)):
            self.assertFalse(_use_repeat_kv(capability, torch, transformers, mode))

    def setUp(self):
        cuda = types.SimpleNamespace(is_available=lambda: True, get_device_capability=lambda index: (7, 5),
                                     get_device_name=lambda index: "RTX 2070")
        self.torch = types.SimpleNamespace(cuda=cuda, __version__="2.7.1+cu126")
        self.integration = types.SimpleNamespace(use_gqa_in_sdpa=Mock(return_value=True))
        original_import = importlib.import_module
        def load(name, package=None):
            if name == "transformers.integrations.sdpa_attention":
                return self.integration
            return original_import(name, package)
        self.patches = [patch.dict(sys.modules, {"torch":self.torch}),
            patch("generation_agent.tts_attention.importlib.metadata.version", return_value="4.57.3"),
            patch("generation_agent.tts_attention.importlib.import_module", side_effect=load)]
        for item in self.patches:
            item.start()
            self.addCleanup(item.stop)

    def test_failed_probe_keeps_stock(self):
        original = self.integration.use_gqa_in_sdpa
        with patch("generation_agent.tts_attention._probe_efficient", return_value={"passed":False, "reason":"unsupported"}):
            report = prepare_attention()
        self.assertIs(self.integration.use_gqa_in_sdpa, original)
        self.assertEqual(report["gqa_policy"], "stock")

    def test_cuda_key_expansion(self):
        with patch("generation_agent.tts_attention._probe_efficient", return_value={"passed":True}):
            report = prepare_attention()
        self.assertEqual(report["gqa_policy"], "repeat_kv")
        cpu = types.SimpleNamespace(device=types.SimpleNamespace(type="cpu", index=None))
        cuda = types.SimpleNamespace(device=types.SimpleNamespace(type="cuda", index=0))
        self.assertTrue(self.integration.use_gqa_in_sdpa(None, cpu))
        self.assertFalse(self.integration.use_gqa_in_sdpa(None, cuda))
        second_gpu = types.SimpleNamespace(device=types.SimpleNamespace(type="cuda", index=1))
        self.assertTrue(self.integration.use_gqa_in_sdpa(None, second_gpu))

    def test_disabled_never_probes(self):
        with patch("generation_agent.tts_attention._probe_efficient") as probe:
            report = prepare_attention(mode=TtsAttention.SDPA)
        probe.assert_not_called()
        self.assertEqual(report["gqa_policy"], "stock")

    def test_disable_restores_stock(self):
        original = self.integration.use_gqa_in_sdpa
        with patch("generation_agent.tts_attention._probe_efficient", return_value={"passed":True}):
            prepare_attention()
            prepare_attention(mode=TtsAttention.SDPA)
        self.assertIs(self.integration.use_gqa_in_sdpa, original)

    def test_other_gpu_unchanged(self):
        self.torch.cuda.get_device_capability = lambda index: (8, 6)
        with patch("generation_agent.tts_attention._probe_efficient") as probe:
            report = prepare_attention()
        probe.assert_not_called()
        self.assertEqual(report["gqa_policy"], "stock")
        self.assertEqual(report["flash_attention_2"], "not selected or tested")

    def test_probe_exception_safe(self):
        original = self.integration.use_gqa_in_sdpa
        with patch("generation_agent.tts_attention._probe_efficient", side_effect=RuntimeError("kernel unavailable")):
            report = prepare_attention()
        self.assertIs(self.integration.use_gqa_in_sdpa, original)
        self.assertIn("kernel unavailable", report["probe"]["reason"])


if __name__ == "__main__":
    unittest.main()
